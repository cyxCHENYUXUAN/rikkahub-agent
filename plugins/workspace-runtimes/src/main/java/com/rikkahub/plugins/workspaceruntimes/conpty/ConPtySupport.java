package com.rikkahub.plugins.workspaceruntimes.conpty;

import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Probes the operating system and Windows kernel for ConPTY support without crashing or failing on older platforms.
 */
public final class ConPtySupport {
    private static final Logger LOG = LoggerFactory.getLogger(ConPtySupport.class);
    private static final boolean CONPTY_SUPPORTED;

    static {
        boolean supported = false;
        if (Platform.isWindows()) {
            try {
                NativeLibrary kernel32 = NativeLibrary.getInstance("kernel32");
                Function func = kernel32.getFunction("CreatePseudoConsole");
                supported = (func != null);
            } catch (Throwable t) {
                LOG.debug("ConPTY not available on this platform: {}", t.getMessage());
                supported = false;
            }
        }
        CONPTY_SUPPORTED = supported;
    }

    private ConPtySupport() {}

    public static boolean isSupported() {
        return CONPTY_SUPPORTED;
    }
}
