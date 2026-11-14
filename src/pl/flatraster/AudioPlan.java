package pl.flatraster;

import java.util.ArrayList;
import java.util.List;

/**
 * Where each voice block goes on the recording's time line, at 16 kHz. Gaps (skipped silence) become silence.
 * The last block of a run of speech is filled out with silence; if the next run starts inside it, the newer block wins
 * and the older one is cut there. Pure Java, so it is tested on a computer.
 */
final class AudioPlan {
    static final int FS = 16000, BLOCK = 16000;

    static final class Item {
        final long silence; final byte[] block; final int samples; // silence before the block, then the block's first `samples` samples
        Item(long silence, byte[] block, int samples) { this.silence = silence; this.block = block; this.samples = samples; }
    }

    /** packets: AUDIO packets, any order. */
    static List<Item> of(List<FrvReader.Packet> packets) {
        List<FrvReader.Packet> a = new ArrayList<>();
        for (FrvReader.Packet p : packets) if (p.type == FrvWriter.AUDIO) a.add(p);
        a.sort((x, y) -> Long.compare(x.timeMs, y.timeMs));
        List<Item> out = new ArrayList<>(); long pos = 0; // samples placed so far
        for (int i = 0; i < a.size(); i++) {
            long start = a.get(i).timeMs * FS / 1000;
            long end = start + BLOCK;
            if (i + 1 < a.size()) end = Math.min(end, a.get(i + 1).timeMs * FS / 1000);
            if (start < pos) start = pos; // two packets with the same time: the later one keeps its place after the earlier
            if (end <= start) continue;
            out.add(new Item(start - pos, a.get(i).data, (int) (end - start)));
            pos = end;
        }
        return out;
    }
}
