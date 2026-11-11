package pl.dyktafon;

/**
 * Gives the FlatRaster app the Codec2 Recorder voice path without changing it: the same voice chain
 * and Codec 2 library, with the settings RecService uses by default.
 */
public final class VoiceBridge {
    private long h;
    private final VoiceChain chain;
    public final int samples, bytes, frameMs, mode;

    /** encoder=false makes a decoder only. Throws if the codec does not start. */
    public VoiceBridge(int mode, boolean encoder, boolean denoise) {
        h = C2.create(mode);
        if (h == 0) throw new IllegalStateException("the voice codec did not start");
        this.mode = mode; samples = C2.samples(h); bytes = C2.bytes(h); frameMs = samples / 8;
        if (encoder && mode == 8) C2.eq(h, true); // as in RecService
        // Same defaults as RecService: denoise floor 0.18, treble 1, rumble cut 180 Hz, lift 6 dB, gate 12 dB, compressor 3.
        chain = encoder ? new VoiceChain(samples, denoise, 0.18, 1, null, 180, 6, 12, 3) : null;
    }

    /** Runs the voice chain on one frame in place with automatic level, then encodes it. Returns the level, 0..1. */
    public float encode(short[] pcm, byte[] out) {
        chain.process(pcm, -100, 5, 1);
        C2.encode(h, pcm, out);
        return (float) chain.level;
    }

    public boolean micOverload() { return chain != null && chain.frameClipped; }

    public void decode(byte[] in, short[] out) { C2.decode(h, in, out); }

    public void close() { if (h != 0) { C2.destroy(h); h = 0; } }

    public static String[] modeNames() { return C2.NAMES.clone(); }
    public static int modeOf(String name) { return C2.modeOf(name); }
    public static String nameOf(int mode) { return C2.nameOf(mode); }
}
