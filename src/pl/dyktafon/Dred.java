package pl.dyktafon;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;

/**
 * The .dred recording format. Pure Java, so it also runs in computer tests.
 * 8-byte header: "DRED", format version, DRED bitstream version of the library, quality level q, 20 ms frames per block.
 * Then blocks: a 2-byte length (big endian) and that many bytes. Length 0 is one block of silence.
 * Each block is one second of speech and stands on its own, so the length in seconds is the number of blocks.
 */
final class Dred {
    static final int HEADER = 8, FORMAT = 1;
    /** DRED_EXPERIMENTAL_VERSION in third_party/opus-1.6.1/dnn/dred_config.h. Files with another number can't be played by this library. */
    static final int BITSTREAM = 12;
    static final int FS = 16000, FRAME = 320, BLOCK_FRAMES = 50, BLOCK_SAMPLES = FRAME * BLOCK_FRAMES;
    /** DRED_MAX_DATA_SIZE: no block is longer. */
    static final int MAX_BLOCK = 1000;

    /** The levels offered in the app, finest first. The names are what the page shows; the file name uses the short tag. */
    static final String[] NAMES = {"best", "balanced", "small", "smallest"};
    static final int[] LEVELS = {4, 7, 9, 12};
    static final int DEFAULT_Q = 9;

    static int qOf(String name) { for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(name)) return LEVELS[i]; return -1; }
    /** Level name for a q, or "q" plus the number for a level the app does not offer (files from elsewhere still play). */
    static String nameOf(int q) { for (int i = 0; i < LEVELS.length; i++) if (LEVELS[i] == q) return NAMES[i]; return "q" + q; }

    static byte[] header(int q) { return new byte[]{'D', 'R', 'E', 'D', FORMAT, BITSTREAM, (byte) q, BLOCK_FRAMES}; }

    /** Thrown for a file this app can't play, with a sentence the user can read. */
    static final class BadFile extends IOException { BadFile(String m) { super(m); } }

    /** Checks a header and returns q. */
    static int readHeader(byte[] h) throws BadFile {
        if (h.length < HEADER || h[0] != 'D' || h[1] != 'R' || h[2] != 'E' || h[3] != 'D') {
            if (h.length >= 3 && (h[0] & 255) == 0xc0 && (h[1] & 255) == 0xde && (h[2] & 255) == 0xc2)
                throw new BadFile("This is a Codec 2 recording from an older version of the app, which this version can't play. Open it in version 2.6 and save it as WAV.");
            throw new BadFile("This isn't a .dred recording.");
        }
        if (h[4] != FORMAT || h[5] != BITSTREAM)
            throw new BadFile("This recording was made by a different version of DRED (format " + h[4] + ", bitstream " + h[5] + "). This app can't play it.");
        int q = h[6];
        if (q < 0 || q > 15 || h[7] != BLOCK_FRAMES) throw new BadFile("This .dred file has a damaged header.");
        return q;
    }
    static boolean isCodec2(byte[] h) { return h.length >= 3 && (h[0] & 255) == 0xc0 && (h[1] & 255) == 0xde && (h[2] & 255) == 0xc2; }

    static void writeBlock(OutputStream o, byte[] b, int n) throws IOException { o.write(n >> 8); o.write(n & 255); o.write(b, 0, n); }

    /** A file's header and where each block starts. A block cut short at the end (the app was stopped while writing) is left out. */
    static final class Index {
        final int q; final long[] pos; final int[] len; final int blocks;
        private Index(int q, long[] pos, int[] len, int blocks) { this.q = q; this.pos = pos; this.len = len; this.blocks = blocks; }
        double seconds() { return blocks * (double) BLOCK_SAMPLES / FS; }

        static Index of(File f) throws IOException { try (InputStream in = new BufferedInputStream(new FileInputStream(f), 16384)) { return of(in); } }

        static Index of(InputStream in) throws IOException {
            byte[] h = new byte[HEADER]; int got = 0, k;
            while (got < HEADER && (k = in.read(h, got, HEADER - got)) > 0) got += k;
            if (got < HEADER) throw new BadFile(got >= 3 && isCodec2(h) ? readHeaderMessage(h) : "This isn't a .dred recording.");
            int q = readHeader(h);
            long[] pos = new long[256]; int[] len = new int[256]; int n = 0; long p = HEADER;
            while (true) {
                int hi = in.read(), lo = in.read();
                if (hi < 0 || lo < 0) break;
                int l = hi << 8 | lo;
                if (l > MAX_BLOCK) throw new BadFile("This .dred file is damaged after " + n + " seconds.");
                if (skipFully(in, l) < l) break;
                if (n == pos.length) { pos = java.util.Arrays.copyOf(pos, n * 2); len = java.util.Arrays.copyOf(len, n * 2); }
                pos[n] = p + 2; len[n] = l; n++; p += 2 + l;
            }
            return new Index(q, pos, len, n);
        }

        /** Reads block i into b and returns its length (0 is silence). */
        int read(RandomAccessFile f, int i, byte[] b) throws IOException { f.seek(pos[i]); f.readFully(b, 0, len[i]); return len[i]; }
    }

    private static String readHeaderMessage(byte[] h) { try { readHeader(h); return ""; } catch (BadFile e) { return e.getMessage(); } }

    private static long skipFully(InputStream in, long n) throws IOException {
        long done = 0;
        while (done < n) {
            long s = in.skip(n - done);
            if (s <= 0) { if (in.read() < 0) break; s = 1; }
            done += s;
        }
        return done;
    }
}
