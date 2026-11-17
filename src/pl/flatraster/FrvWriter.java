package pl.flatraster;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Writes a .frv file: posterized video plus DRED voice as small self-checking packets.
 *
 * Header, 14 bytes: "FRV1", version (2, or 3 when the video codec is 3, Exact: each KEY/DELTA packet then holds one picture of jni/flatcode.c, see tools/coder/flatcoder.py for its layout), video codec (0 H.264, 1 HEVC, 2 AV1, 3 Exact), width (2), height (2), DRED bitstream version, DRED level q, CRC16 (2).
 * Version 1 (up to 0.12) had Codec 2 voice: byte 10 was the Codec 2 mode, byte 11 was 0.
 * Codec 4, Puppet (also version 3): a KEY packet holds one picture of the Exact coder (key pictures only, the background; the person is
 * still in it and is drawn faded); a POINTS packet (type 5, one per picture time, right after the KEY packet when both have the same time) holds the
 * landmarks of face (22 points, tools/edge/marks.py FACE_IDX) and upper body (BlazePose points 0 to 24) coded by jni/motion.c; points are integer units of
 * width/320 of the picture, y in the same unit. The point coder restarts at every KEY packet (PuppetEncoder).
 * An AUDIO packet holds one DRED block, one second of sound; its time is the time of the block's first sound. An empty one is a second of silence.
 * Packet: 0xF5 0x7A, type, time in ms (4), payload length (3), payload, CRC16 (2) over type..payload.
 * All numbers are big-endian. Each packet can be checked alone, so a damaged part costs only itself.
 */
final class FrvWriter implements Closeable {
    static final int VERSION = 2, VERSION_EXACT = 3, HEADER = 14, OVERHEAD = 12;
    static final int SYNC0 = 0xF5, SYNC1 = 0x7A;
    static final int CONFIG = 1, KEY = 2, DELTA = 3, AUDIO = 4, POINTS = 5;
    static final int AVC = 0, HEVC = 1, AV1 = 2, EXACT = 3, PUPPET = 4; // EXACT: the posterized picture coded by Exact (jni/flatcode.c), no phone video codec; file version 3
    static final String[] MIME = {"video/avc", "video/hevc", "video/av01"};
    static final String[] CODEC_NAME = {"H.264", "HEVC", "AV1", "Exact", "Puppet"};

    private final OutputStream out;
    volatile long bytes, videoBytes, audioBytes; // read by the status line from another thread
    private long lastFlush = System.currentTimeMillis();

    FrvWriter(File f, int codec, int width, int height, int bitstream, int q) throws IOException {
        this(new BufferedOutputStream(new FileOutputStream(f)), codec, width, height, bitstream, q);
    }

    FrvWriter(OutputStream o, int codec, int width, int height, int bitstream, int q) throws IOException {
        out = o;
        byte[] h = {'F', 'R', 'V', '1', (byte) (codec >= EXACT ? VERSION_EXACT : VERSION), (byte) codec, (byte) (width >> 8), (byte) width, (byte) (height >> 8), (byte) height, (byte) bitstream, (byte) q, 0, 0};
        int c = crc16(h, 0, 12);
        h[12] = (byte) (c >> 8); h[13] = (byte) c;
        out.write(h); bytes = HEADER;
    }

    synchronized void write(int type, long timeMs, byte[] data, int off, int len) throws IOException {
        if (len > 0xFFFFFF) throw new IOException("packet too large");
        byte[] p = new byte[OVERHEAD + len];
        p[0] = (byte) SYNC0; p[1] = (byte) SYNC1; p[2] = (byte) type;
        p[3] = (byte) (timeMs >> 24); p[4] = (byte) (timeMs >> 16); p[5] = (byte) (timeMs >> 8); p[6] = (byte) timeMs;
        p[7] = (byte) (len >> 16); p[8] = (byte) (len >> 8); p[9] = (byte) len;
        System.arraycopy(data, off, p, 10, len);
        int c = crc16(p, 2, 8 + len);
        p[10 + len] = (byte) (c >> 8); p[11 + len] = (byte) c;
        out.write(p);
        bytes += p.length;
        if (type == AUDIO) audioBytes += p.length; else videoBytes += p.length;
        long now = System.currentTimeMillis();
        if (now - lastFlush >= 1000) { out.flush(); lastFlush = now; } // a crash loses at most about a second
    }

    @Override public synchronized void close() throws IOException { out.close(); }

    /** CRC-16/CCITT-FALSE. */
    static int crc16(byte[] b, int off, int len) {
        int c = 0xFFFF;
        for (int i = off; i < off + len; i++) {
            c ^= (b[i] & 255) << 8;
            for (int k = 0; k < 8; k++) c = (c & 0x8000) != 0 ? (c << 1) ^ 0x1021 : c << 1;
            c &= 0xFFFF;
        }
        return c;
    }

    /** Packs the encoder's setup buffers (csd-0, csd-1, ...) into one CONFIG payload. */
    static byte[] packConfig(byte[][] csd) {
        int n = 1; for (byte[] c : csd) n += 2 + c.length;
        byte[] p = new byte[n]; int o = 0;
        p[o++] = (byte) csd.length;
        for (byte[] c : csd) { p[o++] = (byte) (c.length >> 8); p[o++] = (byte) c.length; System.arraycopy(c, 0, p, o, c.length); o += c.length; }
        return p;
    }

    /** Reverse of packConfig. Returns null if the payload is not valid. */
    static byte[][] unpackConfig(byte[] p) {
        if (p.length < 1) return null;
        byte[][] csd = new byte[p[0] & 255][]; int o = 1;
        for (int i = 0; i < csd.length; i++) {
            if (o + 2 > p.length) return null;
            int len = (p[o] & 255) << 8 | (p[o + 1] & 255); o += 2;
            if (o + len > p.length) return null;
            csd[i] = java.util.Arrays.copyOfRange(p, o, o + len); o += len;
        }
        return csd;
    }
}
