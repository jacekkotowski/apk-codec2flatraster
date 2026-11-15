package pl.flatraster;

import android.media.AudioFormat;
import android.media.AudioRecord;
import pl.dyktafon.MicRoute;
import pl.dyktafon.NoiseStore;
import pl.dyktafon.SoundSettings;
import pl.dyktafon.VoiceBridge;

/** Microphone -> Codec2 Recorder voice chain -> DRED, one packet per one-second block (VoicePackets). Capture settings mirror RecService. */
final class AudioCapture {
    interface Sink { void audio(long timeMs, byte[] data, int len) throws java.io.IOException; }

    volatile float level, gain, vad;
    volatile boolean overload, quiet, sampleReq, sampling; // quiet: silence is being skipped right now
    volatile String error, notice, routed = "";
    private volatile boolean running = true;
    private final Thread thread;

    /** startNs: System.nanoTime() at the start of the recording, so sound and picture share one clock. */
    /** skipSilence: store nothing while nobody speaks; 400 ms before each start of speech is kept, as in Codec2 Recorder. */
    /** set: Codec2 Recorder's sound settings; the rec level, sensitivity and hold in it are read live. */
    AudioCapture(final android.content.Context ctx, final int q, final SoundSettings set, final long startNs, final boolean skipSilence, final Sink sink) {
        thread = VoiceBridge.thread(() -> loop(ctx, q, set, startNs, skipSilence, sink), "audio");
        thread.start();
    }

    private void loop(android.content.Context ctx, int q, SoundSettings set, long startNs, boolean skip, Sink sink) {
        VoiceBridge vb = null; AudioRecord rec = null; android.media.AudioDeviceInfo target = null;
        try {
            vb = new VoiceBridge(q, set, NoiseStore.load(ctx));
            final int n = VoiceBridge.CHUNK, fs = VoiceBridge.FS;
            int min = AudioRecord.getMinBufferSize(fs, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            target = set.device != 0 ? MicRoute.route(ctx, set.device) : null;
            rec = new AudioRecord(MicRoute.source(target, set.source), fs, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 2 * fs));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("the microphone did not start");
            if (target != null) rec.setPreferredDevice(target);
            rec.startRecording();
            android.media.AudioDeviceInfo rd = rec.getRoutedDevice();
            String rn = rd == null ? null : MicRoute.micName(rd); routed = rn == null ? "" : rn;
            long silentChunks = 0; boolean warned = false, clipWarned = false;
            long offsetMs = Math.max(0, (System.nanoTime() - startNs) / 1_000_000); // the microphone opens a little after the camera
            VoicePackets vp = new VoicePackets(vb, skip, offsetMs, sink::audio);
            short[] pcm = new short[n];
            while (running) {
                int got = 0;
                while (got < n && running) { int r = rec.read(pcm, got, n - got); if (r < 0) throw new IllegalStateException("read error " + r); got += r; }
                if (got < n) break;
                boolean allZero = true; for (short v : pcm) if (Math.abs(v) > 1) { allZero = false; break; }
                if (allZero) { if (!warned && ++silentChunks * VoiceBridge.CHUNK_MS > 3000) { warned = true; notice = "No sound from this microphone. Try another one, or switch the headset off and on."; } } else silentChunks = 0;
                if (sampleReq) { sampleReq = false; vb.startNoiseSample(); sampling = true; }
                level = vp.chunk(pcm);
                double[] prof = vb.takeNoiseSample();
                if (prof != null) { NoiseStore.save(ctx, prof); sampling = false; notice = "Noise sample saved"; }
                if (vb.micOverload()) overload = true;
                if (vb.inputClipped() && !clipWarned) { clipWarned = true; notice = "The microphone itself is overloading before the app receives the sound. Move it further away or try another audio source."; }
                quiet = vp.quiet; gain = (float) vb.gain(); vad = (float) vb.voiceMeter();
            }
            vp.finish();
        } catch (Exception e) {
            error = "Sound recording stopped: " + e.getMessage();
        } finally {
            if (rec != null) { try { rec.stop(); } catch (Exception ignored) {} rec.release(); }
            try { MicRoute.unroute(ctx, target); } catch (Exception ignored) {}
            if (vb != null) vb.close();
            level = 0; sampling = false;
        }
    }

    /** Stops and waits until the last packet is written. */
    void stop() {
        running = false;
        try { thread.join(3000); } catch (InterruptedException ignored) {}
    }
}
