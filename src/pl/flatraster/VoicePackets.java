package pl.flatraster;

import pl.dyktafon.VoiceBridge;

/**
 * Sound after the microphone: voice chain, skip silence, DRED coder, one AUDIO packet per one-second block.
 * Pure Java apart from the coder, so a computer test can feed it a sound file.
 * Skip silence gates the sound before the coder. While closed it keeps 400 ms of processed sound and feeds it when speech starts,
 * so the start of a word is not cut. When speech stops the coder's last block is filled out with silence and a fresh coder starts
 * at the next speech, so a block's time is always the time of its first sound.
 */
final class VoicePackets {
    interface Sink { void audio(long timeMs, byte[] data, int len) throws java.io.IOException; }

    private static final int PRE = 400 / VoiceBridge.CHUNK_MS;
    private final VoiceBridge vb; private final boolean skip; private final long offsetMs; private final Sink sink;
    private final short[][] pre = new short[PRE][VoiceBridge.CHUNK]; private int preW, preC;
    private boolean wasOpen; private long chunks, runStartMs, runBlocks;
    /** True while silence is being skipped. */
    boolean quiet;

    /** offsetMs: time of the first chunk on the recording's clock. */
    VoicePackets(VoiceBridge vb, boolean skipSilence, long offsetMs, Sink sink) { this.vb = vb; skip = skipSilence; this.offsetMs = offsetMs; this.sink = sink; }

    /** One chunk of VoiceBridge.CHUNK samples at 16 kHz, processed in place. Returns the level, 0..1. */
    float chunk(short[] pcm) throws java.io.IOException {
        float level = vb.process(pcm);
        long now = offsetMs + chunks * VoiceBridge.CHUNK_MS; chunks++;
        boolean open = !skip || vb.voice;
        quiet = !open;
        if (open) {
            if (!wasOpen) { // a new run of speech, starting with the pre-roll
                runStartMs = now - (long) preC * VoiceBridge.CHUNK_MS; runBlocks = 0;
                for (int k = preC; k > 0; k--) vb.code(pre[(preW - k + PRE) % PRE], this::block);
                preC = 0;
            }
            vb.code(pcm, this::block);
        } else {
            if (wasOpen) vb.finish(this::block);
            System.arraycopy(pcm, 0, pre[preW], 0, VoiceBridge.CHUNK); preW = (preW + 1) % PRE; if (preC < PRE) preC++;
        }
        wasOpen = open;
        return level;
    }

    private void block(byte[] b, int n) throws java.io.IOException { sink.audio(runStartMs + runBlocks * VoiceBridge.BLOCK_MS, b, n); runBlocks++; }

    /** At the end of the recording: the last block. */
    void finish() throws java.io.IOException { if (wasOpen) vb.finish(this::block); wasOpen = false; }
}
