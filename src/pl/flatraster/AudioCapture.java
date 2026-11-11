package pl.flatraster;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import pl.dyktafon.VoiceBridge;

/** Microphone -> Codec2 Recorder voice chain -> Codec 2, in packets of about one second. Capture settings mirror RecService. */
final class AudioCapture {
    interface Sink { void audio(long timeMs, byte[] data, int len) throws java.io.IOException; }

    volatile float level;
    volatile boolean overload, quiet; // quiet: silence is being skipped right now
    volatile String error;
    private volatile boolean running = true;
    private final Thread thread;

    /** startNs: System.nanoTime() at the start of the recording, so sound and picture share one clock. */
    /** skipSilence: store nothing while nobody speaks; 400 ms before each start of speech is kept, as in Codec2 Recorder. */
    AudioCapture(final int mode, final long startNs, final boolean skipSilence, final Sink sink) {
        thread = new Thread(() -> loop(mode, startNs, skipSilence, sink), "audio");
        thread.start();
    }

    private void loop(int mode, long startNs, boolean skip, Sink sink) {
        VoiceBridge vb = null; AudioRecord rec = null;
        try {
            vb = new VoiceBridge(mode, true, false);
            int n = vb.samples, nb = vb.bytes;
            int min = AudioRecord.getMinBufferSize(8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 16000));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("the microphone did not start");
            rec.startRecording();
            long offsetMs = Math.max(0, (System.nanoTime() - startNs) / 1_000_000); // the microphone opens a little after the camera
            short[] pcm = new short[n]; byte[] bits = new byte[nb];
            int group = Math.max(1, 1000 / vb.frameMs), have = 0; byte[] pack = new byte[group * nb];
            long frames = 0, packStart = 0;
            // Encoded frames from the last 400 ms of silence, so the start of a word is not cut. Always fewer than one packet.
            int preN = Math.max(1, 400 / vb.frameMs), preW = 0, preC = 0; byte[][] pre = new byte[preN][nb];
            boolean wasOpen = false;
            while (running) {
                int got = 0;
                while (got < n && running) { int r = rec.read(pcm, got, n - got); if (r < 0) throw new IllegalStateException("read error " + r); got += r; }
                if (got < n) break;
                level = vb.encode(pcm, bits);
                if (vb.micOverload()) overload = true;
                boolean open = !skip || vb.voice;
                quiet = !open;
                if (open) {
                    if (!wasOpen && preC > 0) { // `have` is 0 here: the packet was flushed when speech stopped
                        packStart = offsetMs + (frames - preC) * vb.frameMs;
                        for (int k = preC; k > 0; k--) { System.arraycopy(pre[(preW - k + preN) % preN], 0, pack, have * nb, nb); have++; }
                        preC = 0;
                    }
                    if (have == 0) packStart = offsetMs + frames * vb.frameMs;
                    System.arraycopy(bits, 0, pack, have * nb, nb); have++;
                    if (have == group) { sink.audio(packStart, pack, have * nb); have = 0; }
                } else {
                    if (have > 0) { sink.audio(packStart, pack, have * nb); have = 0; }
                    System.arraycopy(bits, 0, pre[preW], 0, nb); preW = (preW + 1) % preN; if (preC < preN) preC++;
                }
                wasOpen = open; frames++;
            }
            if (have > 0) sink.audio(packStart, pack, have * nb);
        } catch (Exception e) {
            error = "Sound recording stopped: " + e.getMessage();
        } finally {
            if (rec != null) { try { rec.stop(); } catch (Exception ignored) {} rec.release(); }
            if (vb != null) vb.close();
            level = 0;
        }
    }

    /** Stops and waits until the last packet is written. */
    void stop() {
        running = false;
        try { thread.join(3000); } catch (InterruptedException ignored) {}
    }
}
