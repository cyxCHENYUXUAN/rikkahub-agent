package com.rikkahub.plugins.workspaceruntimes;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinNT.HANDLEByReference;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

/** A bound disk object. Never reopens a validated name for data I/O or cleanup. */
final class WindowsFile implements AutoCloseable {
    interface Nt extends StdCallLibrary {
        Nt INSTANCE = Native.load("ntdll", Nt.class);
        int NtCreateFile(HANDLEByReference file, int access, Pointer attributes, Pointer status,
                Pointer allocation, int fileAttributes, int share, int disposition, int options,
                Pointer ea, int eaLength);
        int NtSetInformationFile(HANDLE file, Pointer status, Pointer information, int length, int kind);
        int NtReadFile(HANDLE file, Pointer event, Pointer apc, Pointer context, Pointer status,
                Pointer buffer, int length, Pointer offset, Pointer key);
    }
    static final int READ = 0x80000000;
    static final int WRITE = 0x40000000;
    static final int DELETE = 0x10000;
    static final int OPEN = 1;
    static final int CREATE = 2;
    static final int OPEN_IF = 3;
    private HANDLE handle;
    final String name;

    private WindowsFile(HANDLE handle, String name) { this.handle = handle; this.name = name; }

    static WindowsFile volume(Path root) {
        // UNC/network and Win32 device namespaces are not this adapter's filesystem boundary.
        if (root == null || !root.toString().matches("(?i)[a-z]:\\\\")) throw LocalPaths.denied();
        HANDLE handle = Kernel32.INSTANCE.CreateFile(root.toString(), READ, 1, null, 3, 0x02200000, null);
        if (!WindowsOwnership.valid(handle)) throw unavailable(Kernel32.INSTANCE.GetLastError());
        WindowsFile file = new WindowsFile(handle, root.toString());
        try { file.requireDirectory(); return file; }
        catch (RuntimeException e) { file.close(); throw e; }
    }

    WindowsFile open(String child, int access, int disposition, boolean directory, boolean allowMissing) {
        return open(child, access, disposition, directory ? 1 : 0, allowMissing);
    }
    WindowsFile openAny(String child, boolean allowMissing) {
        return open(child, READ, OPEN, -1, allowMissing);
    }
    private WindowsFile open(String child, int access, int disposition, int kind, boolean allowMissing) {
        LocalPaths.validateName(child);
        if (child.isEmpty() || child.equals(".")) throw LocalPaths.denied();
        byte[] bytes = child.getBytes(StandardCharsets.UTF_16LE);
        if (bytes.length > 65532) throw LocalPaths.denied();
        int p = Native.POINTER_SIZE;
        try (Memory text = new Memory(bytes.length + 2L);
             Memory unicode = new Memory(p == 8 ? 16 : 8);
             Memory attributes = new Memory(p == 8 ? 48 : 24);
             Memory status = new Memory(2L * p)) {
            text.clear(); text.write(0, bytes, 0, bytes.length);
            unicode.clear(); unicode.setShort(0, (short) bytes.length);
            unicode.setShort(2, (short) (bytes.length + 2)); unicode.setPointer(p == 8 ? 8 : 4, text);
            attributes.clear(); attributes.setInt(0, (int) attributes.size());
            attributes.setPointer(p == 8 ? 8 : 4, handle.getPointer());
            attributes.setPointer(p == 8 ? 16 : 8, unicode);
            // OBJ_CASE_INSENSITIVE | OBJ_DONT_REPARSE. One component, relative to a bound handle.
            attributes.setInt(p == 8 ? 24 : 12, 0x40 | 0x1000);
            HANDLEByReference result = new HANDLEByReference();
            int code = Nt.INSTANCE.NtCreateFile(result, access | 0x100000 | 0x80, attributes, status,
                    null, 0x80, 1, disposition, 0x200000 | 0x20 | (kind == 1 ? 1 : kind == 0 ? 0x40 : 0), null, 0);
            if (allowMissing && (code == 0xc0000034 || code == 0xc000003a)) return null;
            check(code);
            WindowsFile file = new WindowsFile(result.getValue(), child);
            try {
                if (kind == 1) file.requireDirectory();
                else if (kind == 0) file.requireRegularFile();
                else file.attributes();
                return file;
            } catch (RuntimeException e) { file.close(); throw e; }
        }
    }

    Temporary temporary() {
        // CREATE is atomic; this very handle owns the file through write, rename, or deletion.
        return new Temporary(open(".runtime-" + UUID.randomUUID() + ".tmp", WRITE | DELETE, CREATE, false, false));
    }
    static final class Temporary implements AutoCloseable {
        final WindowsFile file;
        private boolean published;
        private boolean closed;
        private Temporary(WindowsFile file) { this.file = file; }
        void publish(String name) { file.rename(name); published = true; }
        @Override public void close() {
            if (closed) return;
            closed = true;
            try { if (!published) file.delete(); } finally { file.close(); }
        }
    }
    HANDLE handle() { return handle; }

    private int attributes() {
        try (Memory info = new Memory(8)) {
            if (!Kernel32.INSTANCE.GetFileInformationByHandleEx(handle, 9, info, new DWORD(8)))
                throw unavailable(Kernel32.INSTANCE.GetLastError());
            int attributes = info.getInt(0);
            if ((attributes & 0x400) != 0) throw LocalPaths.denied();
            return attributes;
        }
    }
    void requireDirectory() { if ((attributes() & 0x10) == 0) throw LocalPaths.denied(); }
    void requireRegularFile() {
        if ((attributes() & 0x10) != 0 || Kernel32.INSTANCE.GetFileType(handle) != 1) throw LocalPaths.denied();
    }
    long size() {
        try (Memory info = new Memory(24)) {
            if (!Kernel32.INSTANCE.GetFileInformationByHandleEx(handle, 1, info, new DWORD(24)))
                throw unavailable(Kernel32.INSTANCE.GetLastError());
            return info.getLong(8);
        }
    }
    byte[] read(long offset, int maximum) {
        byte[] bytes = new byte[maximum];
        int used = 0;
        try (Memory buffer = new Memory(Math.max(1, Math.min(maximum, 8192)));
             Memory status = new Memory(2L * Native.POINTER_SIZE); Memory position = new Memory(8)) {
            while (used < maximum) {
                position.setLong(0, offset + used);
                int code = Nt.INSTANCE.NtReadFile(handle, null, null, null, status, buffer,
                        Math.min(maximum - used, 8192), position, null);
                if (code == 0xc0000011) break; // STATUS_END_OF_FILE
                check(code);
                int count = (int) (Native.POINTER_SIZE == 8 ? status.getLong(8) : status.getInt(4));
                if (count == 0) break;
                buffer.read(0, bytes, used, count); used += count;
            }
        }
        return Arrays.copyOf(bytes, used);
    }
    int write(byte[] bytes, int offset, int length) {
        int written = 0;
        while (written < length) written += writeChunk(bytes, offset + written, length - written);
        return written;
    }
    int writeChunk(byte[] bytes, int offset, int length) {
        byte[] chunk = Arrays.copyOfRange(bytes, offset, offset + Math.min(length, 8192));
        IntByReference count = new IntByReference();
        if (!Kernel32.INSTANCE.WriteFile(handle, chunk, chunk.length, count, null) || count.getValue() == 0)
            throw unavailable(Kernel32.INSTANCE.GetLastError());
        return count.getValue();
    }
    interface EntryVisitor { boolean visit(String name, boolean directory); }
    void entries(EntryVisitor visitor) {
        try (Memory buffer = new Memory(65536)) {
            boolean restart = true;
            for (;;) {
                requireDirectory();
                buffer.clear();
                boolean success = Kernel32.INSTANCE.GetFileInformationByHandleEx(handle, restart ? 11 : 10,
                        buffer, new DWORD(buffer.size()));
                restart = false;
                if (!success) {
                    int error = Kernel32.INSTANCE.GetLastError();
                    if (error == 18) return; // ERROR_NO_MORE_FILES
                    throw unavailable(error);
                }
                int offset = 0;
                for (;;) {
                    int next = buffer.getInt(offset), length = buffer.getInt(offset + 60L);
                    if (length < 0 || (length & 1) != 0 || offset + 104L + length > buffer.size())
                        throw new IllegalStateException("Invalid native directory information");
                    String child = new String(buffer.getByteArray(offset + 104L, length), StandardCharsets.UTF_16LE);
                    if (!child.equals(".") && !child.equals("..")) {
                        LocalPaths.validateName(child);
                        if ((buffer.getInt(offset + 56L) & 0x400) != 0) throw LocalPaths.denied();
                        if (!visitor.visit(child, (buffer.getInt(offset + 56L) & 0x10) != 0)) return;
                    }
                    if (next == 0) break;
                    if (next < 104 || offset + (long) next + 104 > buffer.size())
                        throw new IllegalStateException("Invalid native directory information");
                    offset += next;
                }
            }
        }
    }
    void appendPosition() {
        // FilePositionInformation accepts a concrete offset, not FILE_WRITE_TO_END_OF_FILE.
        try (Memory information = new Memory(8); Memory status = new Memory(2L * Native.POINTER_SIZE)) {
            information.setLong(0, size());
            check(Nt.INSTANCE.NtSetInformationFile(handle, status, information, 8, 14));
        }
    }
    void rename(String target) {
        LocalPaths.validateName(target);
        requireRegularFile();
        byte[] bytes = target.getBytes(StandardCharsets.UTF_16LE);
        int rootOffset = Native.POINTER_SIZE == 8 ? 8 : 4;
        int lengthOffset = rootOffset + Native.POINTER_SIZE;
        try (Memory information = new Memory(lengthOffset + 4L + bytes.length + 8);
             Memory status = new Memory(2L * Native.POINTER_SIZE)) {
            information.clear(); information.setByte(0, (byte) 1);
            // Null RootDirectory + simple name means SAME directory. Non-null reopens the
            // directory for FILE_WRITE_DATA (IopOpenLinkOrRenameTarget), defeating strict sharing.
            information.setInt(lengthOffset, bytes.length);
            information.write(lengthOffset + 4L, bytes, 0, bytes.length);
            check(Nt.INSTANCE.NtSetInformationFile(handle, status, information, (int) information.size(), 10));
        }
    }
    void delete() {
        try (Memory information = new Memory(1); Memory status = new Memory(2L * Native.POINTER_SIZE)) {
            information.setByte(0, (byte) 1);
            check(Nt.INSTANCE.NtSetInformationFile(handle, status, information, 1, 13));
        }
    }
    static final class NativeFailure extends IllegalStateException {
        final int status;
        NativeFailure(int status) {
            super("Stable filesystem access unavailable (NTSTATUS " + Integer.toHexString(status) + ")");
            this.status = status;
        }
        boolean missing() { return status == 0xc0000034 || status == 0xc000003a; }
    }
    static void check(int status) { if (status < 0) throw new NativeFailure(status); }
    private static IllegalStateException unavailable(int error) {
        return new IllegalStateException("Stable filesystem access unavailable (native error " + error + ")");
    }
    @Override public void close() { WindowsOwnership.closeHandle(handle); handle = null; }
}
