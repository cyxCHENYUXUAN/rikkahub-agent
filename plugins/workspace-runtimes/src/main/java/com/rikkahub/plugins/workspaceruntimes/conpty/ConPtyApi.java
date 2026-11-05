package com.rikkahub.plugins.workspaceruntimes.conpty;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/**
 * Native bindings for Windows Pseudo Console (ConPTY) APIs available in Windows 10 1809+ (Build 17763+).
 */
public interface ConPtyApi extends StdCallLibrary {
    ConPtyApi INSTANCE = Native.load("kernel32", ConPtyApi.class, W32APIOptions.UNICODE_OPTIONS);

    @Structure.FieldOrder({"X", "Y"})
    class COORD extends Structure implements Structure.ByValue {
        public short X;
        public short Y;

        public COORD() {}

        public COORD(short x, short y) {
            this.X = x;
            this.Y = y;
        }
    }

    int CreatePseudoConsole(COORD size, WinNT.HANDLE hInput, WinNT.HANDLE hOutput, int dwFlags, PointerByReference phPC);

    int ResizePseudoConsole(Pointer hPC, COORD size);

    void ClosePseudoConsole(Pointer hPC);

    boolean InitializeProcThreadAttributeList(Pointer list, int count, int flags, BaseTSD.ULONG_PTRByReference size);

    boolean UpdateProcThreadAttribute(Pointer list, int flags, BaseTSD.ULONG_PTR attribute,
                                     Pointer value, BaseTSD.SIZE_T length, Pointer previous, Pointer returned);

    void DeleteProcThreadAttributeList(Pointer list);

    boolean CreateProcessW(String application, char[] command, Pointer processSecurity, Pointer threadSecurity,
                           boolean inherit, int flags, Pointer environment, String directory,
                           Pointer startup, WinBase.PROCESS_INFORMATION info);
}
