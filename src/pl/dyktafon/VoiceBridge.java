package pl.dyktafon;

/**
 * Gives the FlatRaster app the Codec2 Recorder 2.7 voice path: the same 16 kHz voice chain and DRED coder,
 * with the settings RecService uses by default.
 */
public final class VoiceBridge implements AutoCloseable {
    public static final int FS = Dred.FS, CHUNK = 2 * Dred.FRAME, CHUNK_MS = CHUNK * 1000 / FS, BLOCK_MS = Dred.BLOCK_SAMPLES * 1000 / FS, BLOCK_SAMPLES = Dred.BLOCK_SAMPLES;
    public interface Sink { void block(byte[] b, int n) throws java.io.IOException; }

    private final VoiceChain chain; private DredLib.Coder coder; public final int q; private final SoundSettings set;
    private boolean sampling;
    private final short[] half = new short[Dred.FRAME];
    /** True while the last chunk held speech (with about a second of hold after it). */
    public boolean voice = true;

    /** Encoder at level q with Codec2 Recorder's sound settings and saved noise sample (or null). Throws if the coder does not start. */
    public VoiceBridge(int q, SoundSettings s, double[] noise) {
        this.q = q; set = s;
        chain = new VoiceChain(FS, VoiceChain.CEILING_DRED, CHUNK, s.denoise, s.floor, s.treble, noise, s.rumble, s.lift, s.gate, s.comp);
        coder = new DredLib.Coder(q);
    }
    /** With the defaults of Codec2 Recorder. */
    public VoiceBridge(int q) { this(q, new SoundSettings(), null); }

    /** Runs the voice chain on one CHUNK of sound in place. Rec level, voice sensitivity and hold are read live from the settings. Returns the level, 0..1. Sets voice. */
    public float process(short[] pcm) {
        voice = chain.process(pcm, set.gainDb(), set.sensitivity(), set.hold());
        return (float) chain.level;
    }

    /** Measures the background for the next 2 s while recording; nobody should speak. */
    public void startNoiseSample() { chain.den.startSample(2); sampling = true; }
    /** The new noise profile once the sample is done, else null. */
    public double[] takeNoiseSample() { if (sampling && !chain.den.sampling()) { sampling = false; return chain.den.profile(); } return null; }
    public boolean sampling() { return sampling; }
    /** Current gain, in the same numbers Codec2 Recorder shows; 0..1 meter of voice above the background. */
    public double gain() { return chain.currentGain; }
    public double voiceMeter() { return chain.meter; }
    /** True once the microphone itself has overloaded, before the app got the sound. */
    public boolean inputClipped() { return chain.inputClipped; }

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
