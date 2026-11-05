package com.rikkahub.plugins.workspaceruntimes.conpty;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinNT.HANDLEByReference;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encapsulates an active Windows ConPTY pseudoconsole session and its child process.
 */
public final class ConPtyProcess implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ConPtyProcess.class);
    private static final int PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE = 0x00020016;
    private static final int EXTENDED_STARTUPINFO_PRESENT = 0x00080000;

    @Structure.FieldOrder({"startup", "attributes"})
    public static class StartupInfoEx extends Structure {
        public WinBase.STARTUPINFO startup = new WinBase.STARTUPINFO();
        public Pointer attributes;
    }

    private final ConPtyApi api;
    private final Pointer hPC;
    private final HANDLE process;
    private final OutputStream stdin;
    private final InputStream stdout;
    private boolean closed;

    private ConPtyProcess(ConPtyApi api, Pointer hPC, HANDLE process, OutputStream stdin, InputStream stdout) {
        this.api = api;
        this.hPC = hPC;
        this.process = process;
        this.stdin = stdin;
        this.stdout = stdout;
    }

    public static ConPtyProcess start(List<String> argv, Path cwd, Map<String, String> env, int cols, int rows) {
        if (!ConPtySupport.isSupported()) {
            throw new UnsupportedOperationException("ConPTY is not supported on this operating system");
        }
        ConPtyApi api = ConPtyApi.INSTANCE;
        Kernel32 k = Kernel32.INSTANCE;

        List<HANDLE> handlesToClose = new ArrayList<>();
        HANDLEByReference hPipeInRead = new HANDLEByReference();
        HANDLEByReference hPipeInWrite = new HANDLEByReference();
        HANDLEByReference hPipeOutRead = new HANDLEByReference();
        HANDLEByReference hPipeOutWrite = new HANDLEByReference();

        WinBase.SECURITY_ATTRIBUTES sa = new WinBase.SECURITY_ATTRIBUTES();
        sa.bInheritHandle = true;
        sa.write();

        if (!k.CreatePipe(hPipeInRead, hPipeInWrite, sa, 0)) {
            throw new IllegalStateException("Failed to create input pipe for ConPTY: " + k.GetLastError());
        }
        handlesToClose.add(hPipeInRead.getValue());
        handlesToClose.add(hPipeInWrite.getValue());

        if (!k.CreatePipe(hPipeOutRead, hPipeOutWrite, sa, 0)) {
            closeAll(handlesToClose);
            throw new IllegalStateException("Failed to create output pipe for ConPTY: " + k.GetLastError());
        }
        handlesToClose.add(hPipeOutRead.getValue());
        handlesToClose.add(hPipeOutWrite.getValue());

        ConPtyApi.COORD size = new ConPtyApi.COORD((short) Math.max(1, cols), (short) Math.max(1, rows));

        PointerByReference phPC = new PointerByReference();
        int hr = api.CreatePseudoConsole(size, hPipeInRead.getValue(), hPipeOutWrite.getValue(), 0, phPC);
        if (hr != 0) {
            closeAll(handlesToClose);
            throw new IllegalStateException("CreatePseudoConsole failed with HRESULT 0x" + Integer.toHexString(hr));
        }

        Pointer hPC = phPC.getValue();
        // ConPTY now owns hPipeInRead and hPipeOutWrite; close our copies
        k.CloseHandle(hPipeInRead.getValue());
        handlesToClose.remove(hPipeInRead.getValue());
        k.CloseHandle(hPipeOutWrite.getValue());
        handlesToClose.remove(hPipeOutWrite.getValue());

        // Setup process attribute list with ConPTY
        Memory attributes = null;
        WinBase.PROCESS_INFORMATION pi = new WinBase.PROCESS_INFORMATION();
        try {
            BaseTSD.ULONG_PTRByReference sizeRef = new BaseTSD.ULONG_PTRByReference();
            api.InitializeProcThreadAttributeList(null, 1, 0, sizeRef);
            attributes = new Memory(sizeRef.getValue().longValue());
            if (!api.InitializeProcThreadAttributeList(attributes, 1, 0, sizeRef)) {
                throw new IllegalStateException("InitializeProcThreadAttributeList failed: " + k.GetLastError());
            }

            if (!api.UpdateProcThreadAttribute(attributes, 0,
                    new BaseTSD.ULONG_PTR(PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE),
                    hPC, new BaseTSD.SIZE_T(Native.POINTER_SIZE), null, null)) {
                throw new IllegalStateException("UpdateProcThreadAttribute failed: " + k.GetLastError());
            }

            StartupInfoEx startupInfoEx = new StartupInfoEx();
            startupInfoEx.startup.cb = new WinDef.DWORD(startupInfoEx.size());
            startupInfoEx.attributes = attributes;
            startupInfoEx.write();

            String commandLine = String.join(" ", argv.stream().map(ConPtyProcess::quote).toList());
            String block = env.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                    .map(e -> e.getKey() + "=" + e.getValue() + "\0")
                    .reduce("", String::concat) + "\0";
            Memory environment = new Memory((block.length() + 1L) * 2);
            environment.setWideString(0, block);

            int creationFlags = EXTENDED_STARTUPINFO_PRESENT;
            boolean created = api.CreateProcessW(null, Native.toCharArray(commandLine), null, null, false,
                    creationFlags, environment, cwd.toAbsolutePath().toString(), startupInfoEx.getPointer(), pi);

            if (!created) {
                int error = k.GetLastError();
                throw new IllegalStateException("CreateProcessW with ConPTY failed (error " + error + ") for command: " + commandLine);
            }

            k.CloseHandle(pi.hThread);

            OutputStream stdinStream = new ConPtyOutputStream(hPipeInWrite.getValue());
            InputStream stdoutStream = new ConPtyInputStream(hPipeOutRead.getValue());

            return new ConPtyProcess(api, hPC, pi.hProcess, stdinStream, stdoutStream);
        } catch (Throwable t) {
            api.ClosePseudoConsole(hPC);
            closeAll(handlesToClose);
            if (pi.hProcess != null && !WinBase.INVALID_HANDLE_VALUE.equals(pi.hProcess)) {
                k.TerminateProcess(pi.hProcess, 1);
                k.CloseHandle(pi.hProcess);
            }
            throw (t instanceof RuntimeException r ? r : new RuntimeException(t));
        } finally {
            if (attributes != null) {
                api.DeleteProcThreadAttributeList(attributes);
                attributes.close();
            }
        }
    }

    public synchronized void resize(int cols, int rows) {
        if (closed) return;
        ConPtyApi.COORD size = new ConPtyApi.COORD((short) Math.max(1, cols), (short) Math.max(1, rows));
        int hr = api.ResizePseudoConsole(hPC, size);
        if (hr != 0) {
            LOG.warn("ResizePseudoConsole returned HRESULT 0x{}", Integer.toHexString(hr));
        }
    }

    public OutputStream stdin() {
        return stdin;
    }

    public InputStream stdout() {
        return stdout;
    }

    public OptionalInt exitCode() {
        IntByReference code = new IntByReference();
        if (Kernel32.INSTANCE.GetExitCodeProcess(process, code)) {
            int val = code.getValue();
            if (val != 259) { // 259 = STILL_ACTIVE
                return OptionalInt.of(val);
            }
        }
        return OptionalInt.empty();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            stdin.close();
        } catch (Exception ignored) {}
        try {
            api.ClosePseudoConsole(hPC);
        } catch (Exception ignored) {}
        try {
            stdout.close();
        } catch (Exception ignored) {}
        if (process != null && !WinBase.INVALID_HANDLE_VALUE.equals(process)) {
            Kernel32.INSTANCE.WaitForSingleObject(process, 2000);
            Kernel32.INSTANCE.TerminateProcess(process, 0);
            Kernel32.INSTANCE.CloseHandle(process);
        }
    }

    private static String quote(String text) {
        if (!text.isEmpty() && text.chars().noneMatch(c -> Character.isWhitespace(c) || c == '"')) {
            return text;
        }
        StringBuilder b = new StringBuilder("\"");
        int slashes = 0;
        for (char c : text.toCharArray()) {
            if (c == '\\') {
                slashes++;
                continue;
            }
            b.append("\\".repeat(c == '"' ? slashes * 2 + 1 : slashes)).append(c);
            slashes = 0;
        }
        return b.append("\\".repeat(slashes * 2)).append('"').toString();
    }

    private static void closeAll(List<HANDLE> handles) {
        for (HANDLE h : handles) {
            if (h != null && !WinBase.INVALID_HANDLE_VALUE.equals(h)) {
                Kernel32.INSTANCE.CloseHandle(h);
            }
        }
    }

    private static final class ConPtyInputStream extends InputStream {
        private HANDLE handle;

        ConPtyInputStream(HANDLE handle) {
            this.handle = handle;
        }

        @Override
        public int read() {
            byte[] b = new byte[1];
            return read(b, 0, 1) < 0 ? -1 : b[0] & 255;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            Objects.checkFromIndexSize(off, len, b.length);
            if (len == 0) return 0;
            byte[] buffer = new byte[Math.min(len, 8192)];
            IntByReference count = new IntByReference();
            if (!Kernel32.INSTANCE.ReadFile(handle, buffer, buffer.length, count, null)) {
                int error = Kernel32.INSTANCE.GetLastError();
                if (error == 109 || error == 232) { // ERROR_BROKEN_PIPE, ERROR_NO_DATA
                    return -1;
                }
                return -1;
            }
            if (count.getValue() == 0) return -1;
            System.arraycopy(buffer, 0, b, off, count.getValue());
            return count.getValue();
        }

        @Override
        public synchronized void close() {
            if (handle != null && !WinBase.INVALID_HANDLE_VALUE.equals(handle)) {
                Kernel32.INSTANCE.CloseHandle(handle);
                handle = null;
            }
        }
    }

    private static final class ConPtyOutputStream extends OutputStream {
        private HANDLE handle;

        ConPtyOutputStream(HANDLE handle) {
            this.handle = handle;
        }

        @Override
        public void write(int value) {
            write(new byte[]{(byte) value}, 0, 1);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            Objects.checkFromIndexSize(off, len, b.length);
            while (len > 0) {
                byte[] chunk = java.util.Arrays.copyOfRange(b, off, off + Math.min(len, 8192));
                IntByReference count = new IntByReference();
                if (!Kernel32.INSTANCE.WriteFile(handle, chunk, chunk.length, count, null) || count.getValue() == 0) {
                    break;
                }
                off += count.getValue();
                len -= count.getValue();
            }
        }

        @Override
        public synchronized void close() {
            if (handle != null && !WinBase.INVALID_HANDLE_VALUE.equals(handle)) {
                Kernel32.INSTANCE.CloseHandle(handle);
                handle = null;
            }
        }
    }
}
