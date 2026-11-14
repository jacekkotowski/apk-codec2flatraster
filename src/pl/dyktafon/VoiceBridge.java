package pl.dyktafon;

/**
 * Gives the FlatRaster app the Codec2 Recorder 2.7 voice path: the same 16 kHz voice chain and DRED coder,
 * with the settings RecService uses by default.
 */
public final class VoiceBridge implements AutoCloseable {
    public static final int FS = Dred.FS, CHUNK = 2 * Dred.FRAME, CHUNK_MS = CHUNK * 1000 / FS, BLOCK_MS = Dred.BLOCK_SAMPLES * 1000 / FS, BLOCK_SAMPLES = Dred.BLOCK_SAMPLES;
    public interface Sink { void block(byte[] b, int n) throws java.io.IOException; }

    private final VoiceChain chain; private DredLib.Coder coder; public final int q;
    private final short[] half = new short[Dred.FRAME];
    /** True while the last chunk held speech (with about a second of hold after it). */
    public boolean voice = true;

    /** Encoder at level q. Throws if the coder does not start. */
    public VoiceBridge(int q, boolean denoise) {
        this.q = q;
        // Same defaults as RecService: denoise floor 0.18, treble 1, rumble cut 180 Hz, lift 6 dB, gate 12 dB, compressor 3.
        chain = new VoiceChain(FS, VoiceChain.CEILING_DRED, CHUNK, denoise, 0.18, 1, null, 180, 6, 12, 3);
        coder = new DredLib.Coder(q);
    }

    /** Runs the voice chain on one CHUNK of sound in place with automatic level. Returns the level, 0..1. Sets voice. */
    public float process(short[] pcm) {
        voice = chain.process(pcm, -100, 5, 1); // sensitivity 5 and 1 s hold: RecService's defaults
        return (float) chain.level;
    }

    /** Codes one processed CHUNK; a finished one-second block goes to the sink. */
    public void code(short[] pcm, Sink sink) throws java.io.IOException {
        for (int h = 0; h < 2; h++) {
            System.arraycopy(pcm, h * Dred.FRAME, half, 0, Dred.FRAME);
            int n = coder.frame(half);
            if (n >= 0) sink.block(coder.buf, n);
        }
    }

    /** Completes the last block with silence and starts a fresh coder, so the next block starts a new run of speech. */
    public void finish(Sink sink) throws java.io.IOException {
        coder.finish(sink::block);
        coder.close(); coder = new DredLib.Coder(q);
    }

    public boolean micOverload() { return chain.frameClipped; }

    @Override public void close() { coder.close(); }

    /** Plays blocks: one in, BLOCK_SAMPLES samples out in pcm. */
    public static final class Decoder implements AutoCloseable {
        private final DredLib.Player p = new DredLib.Player();
        public final short[] pcm = p.pcm;
        public int block(byte[] b, int n) { return p.block(b, n); }
        @Override public void close() { p.close(); }
    }

    public static int bitstream() { return Dred.BITSTREAM; }
    public static String[] levelNames() { return Dred.NAMES.clone(); }
    public static int levelOf(String name) { return Dred.qOf(name); }
    public static String nameOf(int q) { return Dred.nameOf(q); }
    public static final int DEFAULT_Q = Dred.DEFAULT_Q;
    /** Native code needs more stack than a default Android thread has. */
    public static Thread thread(Runnable r, String name) { return DredLib.thread(r, name); }
}
