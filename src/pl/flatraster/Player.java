package pl.flatraster;

import android.graphics.Rect;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import pl.dyktafon.VoiceBridge;

/** Plays a .frv file: pictures to a callback, voice to the speaker, both timed by the packet times. */
final class Player {
    interface View {
        void picture(int[] argb, int w, int h);
        /** tol: 0 for an exact picture, about 40 for one decoded from video (see MainActivity.show). */
        default void picture(int[] argb, int w, int h, int tol) { picture(argb, w, h); }
        void finished(String error);
    }

    volatile long positionMs, durationMs;
    private volatile boolean running = true, soundStop;
    private final Thread thread;

    Player(final File f, final View view) {
        thread = new Thread(() -> {
            String err = null;
            try { play(f, view); } catch (Exception e) { err = "Can't play this recording: " + e.getMessage(); }
            view.finished(running ? err : null);
        }, "player");
        thread.start();
    }

    void stop() { running = false; thread.interrupt(); }

    /** Packets in time order, setup first. Audio is written after the pictures it belongs to, so file order is not play order. */
    static List<FrvReader.Packet> ordered(FrvReader r) {
        List<FrvReader.Packet> l = new ArrayList<>(r.packets);
        l.sort((a, b) -> a.type == FrvWriter.CONFIG || b.type == FrvWriter.CONFIG ? (a.type == b.type ? 0 : a.type == FrvWriter.CONFIG ? -1 : 1) : Long.compare(a.timeMs, b.timeMs));
        return l;
    }

    static MediaFormat videoFormat(FrvReader r) {
        MediaFormat f = MediaFormat.createVideoFormat(FrvWriter.MIME[r.codec], r.width, r.height);
        byte[][] csd = r.config();
        if (csd != null) for (int i = 0; i < csd.length; i++) f.setByteBuffer("csd-" + i, ByteBuffer.wrap(csd[i]));
        return f;
    }

    private void play(File file, View view) throws Exception {
        FrvReader r = new FrvReader(file);
        durationMs = r.durationMs();
        MediaCodec dec = null; Thread audio = null; Exact exact = null; int[] exactPx = null; Points pts = null; boolean puppetBase = false;
        try {
            boolean hasVideo = false, hasAudio = false;
            for (FrvReader.Packet p : r.packets) { hasVideo |= p.type == FrvWriter.KEY; hasAudio |= p.type == FrvWriter.AUDIO; }
            if (hasVideo && r.codec >= FrvWriter.EXACT) {
                exact = new Exact(r.width, r.height); exactPx = new int[r.width * r.height];
                if (r.codec == FrvWriter.PUPPET) pts = new Points(PuppetDraw.SIZES);
            }
            else if (hasVideo) {
                MediaFormat f = videoFormat(r);
                f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
                dec = MediaCodec.createDecoderByType(FrvWriter.MIME[r.codec]);
                dec.configure(f, null, null, 0);
                dec.start();
            }
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean gotKey = false;
            // Sound plays on its own thread from the start, silence included, so it keeps time with the pictures by itself.
            // Decoding a DRED block takes far longer than a Codec 2 frame did, so it must not hold up the pictures.
            if (hasAudio) { final List<AudioPlan.Item> plan = AudioPlan.of(r.packets); audio = VoiceBridge.thread(() -> sound(plan), "sound"); audio.start(); }
            long t0 = System.nanoTime();
            for (FrvReader.Packet p : ordered(r)) {
                if (p.type == FrvWriter.CONFIG) continue;
                while (running) { // wait for this packet's time, showing decoded pictures meanwhile
                    long now = (System.nanoTime() - t0) / 1_000_000;
                    positionMs = Math.min(now, durationMs);
                    if (dec != null) show(dec, info, view, r, 0);
                    if (now >= p.timeMs) break;
                    Thread.sleep(Math.min(20, p.timeMs - now));
                }
                if (!running) return;
                if (p.type == FrvWriter.AUDIO) continue;
                if (pts != null && (p.type == FrvWriter.KEY || p.type == FrvWriter.POINTS)) {   // the puppet stream
                    if (p.type == FrvWriter.KEY) { if (exact.decode(FrvWriter.KEY, p.data, exactPx)) { gotKey = puppetBase = true; pts.reset(); } }
                    else if (puppetBase) {
                        int[] present = new int[PuppetDraw.SIZES.length], units = new int[pts.points * 2];
                        if (pts.decode(p.data, present, units)) view.picture(PuppetDraw.draw(exactPx, r.width, r.height, PuppetDraw.unitsToPixels(units, 0, PuppetDraw.SIZES[0], present[0] != 0, r.width),
                                PuppetDraw.unitsToPixels(units, present[0] != 0 ? PuppetDraw.SIZES[0] : 0, PuppetDraw.SIZES[1], present[1] != 0, r.width)), r.width, r.height, 0);
                    }
                } else if ((p.type == FrvWriter.KEY || p.type == FrvWriter.DELTA) && exact != null) {
                    if (p.type == FrvWriter.KEY) gotKey = true;
                    if (gotKey && exact.decode(p.type, p.data, exactPx)) view.picture(exactPx.clone(), r.width, r.height, 0); // a damaged picture is skipped; the next good key picture repairs it
                } else if ((p.type == FrvWriter.KEY || p.type == FrvWriter.DELTA) && dec != null) {
                    if (p.type == FrvWriter.KEY) gotKey = true;
                    if (!gotKey) continue; // pictures before the first full one can't be decoded
                    int in = dec.dequeueInputBuffer(100_000);
                    if (in >= 0) {
                        ByteBuffer b = dec.getInputBuffer(in); b.clear(); b.put(p.data);
                        dec.queueInputBuffer(in, 0, p.data.length, p.timeMs * 1000, p.type == FrvWriter.KEY ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                    }
                    show(dec, info, view, r, 150_000);
                }
            }
            for (int i = 0; i < 25 && running; i++) { if (dec != null) show(dec, info, view, r, 0); Thread.sleep(20); }
            if (audio != null) audio.join(); // the last block of sound ends up to a second after the last packet time
            positionMs = durationMs;
        } catch (InterruptedException ignored) {
        } finally {
            if (exact != null) exact.close();
            if (pts != null) pts.close();
            if (dec != null) { try { dec.stop(); } catch (Exception ignored) {} dec.release(); }
            if (audio != null) { soundStop = true; audio.interrupt(); try { audio.join(2000); } catch (InterruptedException ignored) {} }
        }
    }

    /** Plays the voice: silence for the gaps, each block decoded as it is needed. A block decodes in about 30 ms on a computer. */
    private void sound(List<AudioPlan.Item> plan) {
        AudioTrack track = null;
        try (VoiceBridge.Decoder d = new VoiceBridge.Decoder()) {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(AudioPlan.FS).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(AudioPlan.FS * 2 * 2).setTransferMode(AudioTrack.MODE_STREAM).build();
            track.play();
            short[] zero = new short[AudioPlan.FS / 10]; long written = 0;
            for (AudioPlan.Item it : plan) {
                for (long left = it.silence; left > 0 && (running && !soundStop); ) { int k = (int) Math.min(zero.length, left); write(track, zero, k); left -= k; }
                int m = Math.min(it.samples, d.block(it.block, it.block.length));
                if (!(running && !soundStop)) break;
                write(track, d.pcm, m); written += it.silence + m;
            }
            if ((running && !soundStop)) { // let the end play out
                track.stop();
                long end = System.currentTimeMillis() + 3000;
                while ((running && !soundStop) && track.getPlaybackHeadPosition() < written && System.currentTimeMillis() < end) Thread.sleep(20);
            }
        } catch (InterruptedException ignored) {
        } catch (Exception e) { /* sound is lost, pictures go on */ }
        finally { if (track != null) { try { track.pause(); track.flush(); } catch (Exception ignored) {} track.release(); } }
    }
    private void write(AudioTrack t, short[] b, int n) { for (int o = 0; o < n && (running && !soundStop); ) { int w = t.write(b, o, n - o); if (w < 0) return; o += w; } }

    /** Shows every picture the decoder has ready, waiting up to waitUs for the first. */
    private void show(MediaCodec dec, MediaCodec.BufferInfo info, View view, FrvReader r, long waitUs) {
        while (running) {
            int out = dec.dequeueOutputBuffer(info, waitUs);
            waitUs = 0;
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue;
            if (out < 0) return;
            Image img = dec.getOutputImage(out);
            if (img != null) {
                Rect c = img.getCropRect();
                int w = Math.min(r.width, c.width()), h = Math.min(r.height, c.height());
                view.picture(toArgb(img, c.left, c.top, w, h), w, h);
                img.close();
            }
            dec.releaseOutputBuffer(out, false);
        }
    }

    /** Limited-range BT.601 YUV to ARGB. */
    static int[] toArgb(Image img, int x0, int y0, int w, int h) {
        Image.Plane[] pl = img.getPlanes();
        ByteBuffer yb = pl[0].getBuffer(), ub = pl[1].getBuffer(), vb = pl[2].getBuffer();
        int ys = pl[0].getRowStride(), yp = pl[0].getPixelStride(), us = pl[1].getRowStride(), up = pl[1].getPixelStride(), vs = pl[2].getRowStride(), vp = pl[2].getPixelStride();
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int sx = x + x0, sy = y + y0;
            int yy = 298 * ((yb.get(sy * ys + sx * yp) & 255) - 16);
            int u = (ub.get((sy / 2) * us + (sx / 2) * up) & 255) - 128, v = (vb.get((sy / 2) * vs + (sx / 2) * vp) & 255) - 128;
            int rr = (yy + 409 * v + 128) >> 8, g = (yy - 100 * u - 208 * v + 128) >> 8, b = (yy + 516 * u + 128) >> 8;
            rr = rr < 0 ? 0 : rr > 255 ? 255 : rr; g = g < 0 ? 0 : g > 255 ? 255 : g; b = b < 0 ? 0 : b > 255 ? 255 : b;
            out[y * w + x] = 0xff000000 | rr << 16 | g << 8 | b;
        }
        return out;
    }
}
