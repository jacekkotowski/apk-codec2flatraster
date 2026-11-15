package pl.dyktafon;

import android.content.Context;
import java.io.File;

/** The saved noise sample, for 16 kHz sound, as in Codec2 Recorder 2.7. */
public final class NoiseStore {
    private static File file(Context c) { return new File(c.getFilesDir(), "noise16.bin"); }
    public static boolean has(Context c) { return file(c).exists(); }
    public static void clear(Context c) { file(c).delete(); }
    public static double[] load(Context c) {
        File f = file(c); if (!f.exists()) return null;
        try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.FileInputStream(f))) {
            double[] p = new double[Denoiser.bins(Dred.FS)]; for (int i = 0; i < p.length; i++) p[i] = in.readDouble(); return p;
        } catch (Exception e) { return null; }
    }
    public static void save(Context c, double[] p) {
        if (p == null || p.length != Denoiser.bins(Dred.FS)) return;
        try (java.io.DataOutputStream o = new java.io.DataOutputStream(new java.io.FileOutputStream(file(c)))) { for (double v : p) o.writeDouble(v); } catch (Exception ignored) {}
    }

    /** Measures 2 s of background through the microphone, as Codec2 Recorder does when it is not recording. Returns null on success, or what went wrong. */
    public static String sample(Context c, SoundSettings s) {
        android.media.AudioRecord rec = null; android.media.AudioDeviceInfo target = null;
        try {
            target = s.device != 0 ? MicRoute.route(c, s.device) : null;
            final int fs = Dred.FS, n = VoiceBridge.CHUNK;
            int min = android.media.AudioRecord.getMinBufferSize(fs, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT);
            rec = new android.media.AudioRecord(MicRoute.source(target, s.source), fs, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 2 * fs));
            if (rec.getState() != android.media.AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("the microphone did not start");
            if (target != null) rec.setPreferredDevice(target);
            rec.startRecording();
            Denoiser d = new Denoiser(fs, n, null); d.reduce = false;
            short[] pcm = new short[n]; double[] x = new double[n]; double hx = 0, hy = 0, hp = Math.pow(0.92, 8000.0 / fs);
            for (int skip = 0; skip < 8; skip++) rec.read(pcm, 0, n); // let the microphone settle
            d.startSample(2);
            while (d.sampling()) {
                int got = 0; while (got < n) { int r = rec.read(pcm, got, n - got); if (r < 0) throw new IllegalStateException("read error " + r); got += r; }
                for (int i = 0; i < n; i++) { double v = pcm[i]; hy = hp * (hy + v - hx); hx = v; x[i] = hy; }
                d.process(x, n);
            }
            save(c, d.profile());
            return null;
        } catch (Exception e) { return e.getMessage() == null ? "the microphone failed" : e.getMessage(); }
        finally { if (rec != null) { try { rec.stop(); } catch (Exception ignored) {} rec.release(); } try { MicRoute.unroute(c, target); } catch (Exception ignored) {} }
    }
}
