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
        void finished(String error);
    }

    volatile long positionMs, durationMs;
    private volatile boolean running = true;
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
        MediaCodec dec = null; AudioTrack track = null; VoiceBridge vb = null;
        try {
            boolean hasVideo = false, hasAudio = false;
            for (FrvReader.Packet p : r.packets) { hasVideo |= p.type == FrvWriter.KEY; hasAudio |= p.type == FrvWriter.AUDIO; }
            if (hasVideo) {
                MediaFormat f = videoFormat(r);
                f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
                dec = MediaCodec.createDecoderByType(FrvWriter.MIME[r.codec]);
                dec.configure(f, null, null, 0);
                dec.start();
            }
            short[] pcm = null; byte[] bits = null;
            if (hasAudio) {
                vb = new VoiceBridge(r.c2mode, false, false);
                pcm = new short[vb.samples]; bits = new byte[vb.bytes];
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(8000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(8000 * 2 * 3).setTransferMode(AudioTrack.MODE_STREAM).build();
                track.play();
            }
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean gotKey = false;
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
                if (p.type == FrvWriter.AUDIO && track != null) {
                    for (int o = 0; o + bits.length <= p.data.length && running; o += bits.length) {
                        System.arraycopy(p.data, o, bits, 0, bits.length);
                        vb.decode(bits, pcm);
                        track.write(pcm, 0, pcm.length);
                    }
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
            for (int i = 0; i < 25 && running; i++) { if (dec != null) show(dec, info, view, r, 0); Thread.sleep(20); } // let the last second of sound out
            positionMs = durationMs;
        } catch (InterruptedException ignored) {
        } finally {
            if (dec != null) { try { dec.stop(); } catch (Exception ignored) {} dec.release(); }
            if (track != null) { try { track.stop(); } catch (Exception ignored) {} track.release(); }
            if (vb != null) vb.close();
        }
    }

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
