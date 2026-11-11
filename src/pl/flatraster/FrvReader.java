package pl.flatraster;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Reads a whole .frv file. Damaged packets are skipped and counted; reading continues at the next good one. */
final class FrvReader {
    static final class Packet {
        final int type; final long timeMs; final byte[] data;
        Packet(int type, long timeMs, byte[] data) { this.type = type; this.timeMs = timeMs; this.data = data; }
    }

    int codec, width, height, c2mode, skippedBytes;
    final List<Packet> packets = new ArrayList<>();

    FrvReader(File f) throws IOException { this(Files.readAllBytes(f.toPath())); }

    FrvReader(byte[] b) throws IOException {
        if (b.length < FrvWriter.HEADER || b[0] != 'F' || b[1] != 'R' || b[2] != 'V' || b[3] != '1') throw new IOException("not a FlatRaster recording");
        if (FrvWriter.crc16(b, 0, 12) != ((b[12] & 255) << 8 | (b[13] & 255))) throw new IOException("the file header is damaged");
        if (b[4] != FrvWriter.VERSION) throw new IOException("made by a newer version of the app");
        codec = b[5] & 255; width = (b[6] & 255) << 8 | (b[7] & 255); height = (b[8] & 255) << 8 | (b[9] & 255); c2mode = b[10] & 255;
        if (codec >= FrvWriter.MIME.length) throw new IOException("unknown video format");
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
