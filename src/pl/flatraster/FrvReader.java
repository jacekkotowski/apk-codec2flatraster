package pl.flatraster;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Reads a whole .frv file. Damaged packets are skipped and counted; reading continues at the next good one. */
final class FrvReader {
    /** The DRED bitstream version this app plays: Dred.BITSTREAM, kept here too so this class stays free of the voice code. */
    static final int BITSTREAM = 12;
    static final class Packet {
        // also made directly by tests
        final int type; final long timeMs; final byte[] data;
        Packet(int type, long timeMs, byte[] data) { this.type = type; this.timeMs = timeMs; this.data = data; }
    }

    int codec, width, height, bitstream, q, skippedBytes;
    final List<Packet> packets = new ArrayList<>();

    FrvReader(File f) throws IOException { this(Files.readAllBytes(f.toPath())); }

    FrvReader(byte[] b) throws IOException {
        if (b.length < FrvWriter.HEADER || b[0] != 'F' || b[1] != 'R' || b[2] != 'V' || b[3] != '1') throw new IOException("not a FlatRaster recording");
        if (FrvWriter.crc16(b, 0, 12) != ((b[12] & 255) << 8 | (b[13] & 255))) throw new IOException("the file header is damaged");
        if (b[4] == 1) throw new IOException("made with Codec 2 voice by version 0.12 or older, which this version can't play");
        if (b[4] != FrvWriter.VERSION && b[4] != FrvWriter.VERSION_EXACT && b[4] != FrvWriter.VERSION_SHORT) throw new IOException("made by a newer version of the app");
        codec = b[5] & 255; width = (b[6] & 255) << 8 | (b[7] & 255); height = (b[8] & 255) << 8 | (b[9] & 255); bitstream = b[10] & 255; q = b[11] & 255;
        if (bitstream != BITSTREAM) throw new IOException("its voice was made by a different version of DRED (" + bitstream + "), which this version can't play");
        if (codec > FrvWriter.PUPPET || (b[4] >= FrvWriter.VERSION_EXACT) != (codec >= FrvWriter.EXACT)) throw new IOException("unknown video format");
        if (codec == FrvWriter.OLD_PUPPET) throw new IOException("a Puppet recording made by version 0.25 or older, whose points this version can't read");
        if (codec == FrvWriter.PUPPET && b[4] == FrvWriter.VERSION_EXACT) throw new IOException("a Puppet recording made by version 0.19, whose points this version can't read");
        if (b[4] == FrvWriter.VERSION_SHORT) { readShort(b); return; }
        int o = FrvWriter.HEADER;
        while (o + FrvWriter.OVERHEAD <= b.length) {
            if ((b[o] & 255) == FrvWriter.SYNC0 && (b[o + 1] & 255) == FrvWriter.SYNC1) {
                int len = (b[o + 7] & 255) << 16 | (b[o + 8] & 255) << 8 | (b[o + 9] & 255);
                int end = o + FrvWriter.OVERHEAD + len;
                if (end <= b.length && FrvWriter.crc16(b, o + 2, 8 + len) == ((b[end - 2] & 255) << 8 | (b[end - 1] & 255))) {
                    long t = (b[o + 3] & 255L) << 24 | (b[o + 4] & 255L) << 16 | (b[o + 5] & 255L) << 8 | (b[o + 6] & 255L);
                    packets.add(new Packet(b[o + 2] & 255, t, java.util.Arrays.copyOfRange(b, o + 10, o + 10 + len)));
                    o = end;
                    continue;
                }
            }
            o++; skippedBytes++; // not a valid packet here: look for the next sync
        }
        skippedBytes += b.length - o;
    }

    /** Version 4 packets (FrvWriter.writeShort). A damaged packet is skipped; times of the packets after it are relative to the last good one until the next
     *  key picture, which carries an absolute time. */
    private void readShort(byte[] b) {
        int o = FrvWriter.HEADER; long last = 0;
        while (o + 6 <= b.length) {
            if ((b[o] & 255) == FrvWriter.SYNC0) {
                int p = o + 1, type = b[p++] & 0x7F; boolean abs = (b[o + 1] & 0x80) != 0; long t = last;
                boolean ok = true;
                if (abs) { if (p + 4 > b.length) ok = false; else { t = (b[p] & 255L) << 24 | (b[p + 1] & 255L) << 16 | (b[p + 2] & 255L) << 8 | (b[p + 3] & 255L); p += 4; } }
                else { long dt = 0; int sh = 0; while (p < b.length && sh < 21) { int c = b[p++] & 255; dt |= (long) (c & 0x7F) << sh; sh += 7; if ((c & 0x80) == 0) break; } t = last + dt; }
                long len = 0; int sh = 0; boolean done = false;
                while (ok && p < b.length && sh < 28) { int c = b[p++] & 255; len |= (long) (c & 0x7F) << sh; sh += 7; if ((c & 0x80) == 0) { done = true; break; } }
                long end = p + len + 2;
                if (ok && done && end <= b.length && FrvWriter.crc16(b, o + 1, (int) (end - 2 - (o + 1))) == ((b[(int) end - 2] & 255) << 8 | (b[(int) end - 1] & 255))) {
                    packets.add(new Packet(type, t, java.util.Arrays.copyOfRange(b, p, p + (int) len)));
                    last = t; o = (int) end;
                    continue;
                }
            }
            o++; skippedBytes++;
        }
        skippedBytes += b.length - o;
    }

    /** Length of the recording in ms. */
    long durationMs() {
        long d = 0;
        for (Packet p : packets) d = Math.max(d, p.timeMs);
        return d;
    }

    /** Setup buffers for the video decoder, or null if the file has none. */
    byte[][] config() {
        for (Packet p : packets) if (p.type == FrvWriter.CONFIG) return FrvWriter.unpackConfig(p.data);
        return null;
    }
}
