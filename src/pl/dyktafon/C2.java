package pl.dyktafon;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

final class C2 {
    static { System.loadLibrary("c2"); }
    static native long create(int mode);
    static native void destroy(long h);
    static native int samples(long h);
    static native int bytes(long h);
    static native void encode(long h, short[] in, byte[] out);
    static native void decode(long h, byte[] in, short[] out);
    static native void eq(long h, boolean on);

    static final int HEADER = 7;
    // Mode numbers come from codec2.h.
    static final String[] NAMES = {"3200", "2400", "1600", "1400", "1300", "1200", "700C", "450"};
    static final int[] MODES = {0, 1, 2, 3, 4, 5, 8, 10};

    static int modeOf(String name) { for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(name)) return MODES[i]; return -1; }
    static String nameOf(int mode) { for (int i = 0; i < MODES.length; i++) if (MODES[i] == mode) return NAMES[i]; return null; }
    static byte[] header(int mode) { return new byte[]{(byte) 0xc0, (byte) 0xde, (byte) 0xc2, 1, 0, (byte) mode, 0}; }

    /** Mode from a .c2 header, or -1 if the header is not valid. */
    static int readMode(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] h = new byte[HEADER];
            if (in.read(h) != HEADER) return -1;
            return modeFromHeader(h);
        }
    }
    static int modeFromHeader(byte[] h) {
        if ((h[0] & 255) != 0xc0 || (h[1] & 255) != 0xde || (h[2] & 255) != 0xc2) return -1;
        return nameOf(h[5]) == null ? -1 : h[5];
    }
}
