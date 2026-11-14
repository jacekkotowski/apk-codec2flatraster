package pl.flatraster;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import pl.dyktafon.VoiceBridge;

/** Microphone -> Codec2 Recorder voice chain -> DRED, one packet per one-second block (VoicePackets). Capture settings mirror RecService. */
final class AudioCapture {
    interface Sink { void audio(long timeMs, byte[] data, int len) throws java.io.IOException; }

    volatile float level;
    volatile boolean overload, quiet; // quiet: silence is being skipped right now
    volatile String error;
    private volatile boolean running = true;
    private final Thread thread;

    /** startNs: System.nanoTime() at the start of the recording, so sound and picture share one clock. */
    /** skipSilence: store nothing while nobody speaks; 400 ms before each start of speech is kept, as in Codec2 Recorder. */
    AudioCapture(final int q, final long startNs, final boolean skipSilence, final Sink sink) {
        thread = VoiceBridge.thread(() -> loop(q, startNs, skipSilence, sink), "audio");
        thread.start();
    }

    private void loop(int q, long startNs, boolean skip, Sink sink) {
        VoiceBridge vb = null; AudioRecord rec = null;
        try {
            vb = new VoiceBridge(q, false);
            final int n = VoiceBridge.CHUNK, fs = VoiceBridge.FS;
            int min = AudioRecord.getMinBufferSize(fs, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, fs, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 2 * fs));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("the microphone did not start");
            rec.startRecording();
            long offsetMs = Math.max(0, (System.nanoTime() - startNs) / 1_000_000); // the microphone opens a little after the camera
            VoicePackets vp = new VoicePackets(vb, skip, offsetMs, sink::audio);
            short[] pcm = new short[n];
            while (running) {
                int got = 0;
                while (got < n && running) { int r = rec.read(pcm, got, n - got); if (r < 0) throw new IllegalStateException("read error " + r); got += r; }
                if (got < n) break;
                level = vp.chunk(pcm);
                if (vb.micOverload()) overload = true;
                quiet = vp.quiet;
            }
            vp.finish();
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
