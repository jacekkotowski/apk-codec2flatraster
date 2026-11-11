package pl.flatraster;

import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.Range;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Encodes posterized I420 frames with the phone's own encoder: AV1 if it has one, else HEVC, else H.264. */
final class VideoEncoder {
    interface Sink { void video(int type, long timeMs, byte[] data) throws java.io.IOException; }

    final int codec;        // FrvWriter.AVC, HEVC or AV1
    final String name;      // encoder component name, for the status line
    final int bitrate;      // bit/s actually asked for, after the encoder's limits
    private final MediaCodec mc;
    private final Sink sink;
    private final int w, h, declFps;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private final HashMap<Long, Long> realTime = new HashMap<>();
    private long frameNo;
    private boolean configSent;

    /** Encoders on this phone for one format that accept the size. Software ones come first: the pictures are tiny and slow,
     *  and software encoders usually accept much lower bitrates than the camera chip's encoder. */
    private static List<MediaCodecInfo> encoders(String mime, int w, int h) {
        List<MediaCodecInfo> hard = new ArrayList<>(), soft = new ArrayList<>();
        for (MediaCodecInfo ci : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!ci.isEncoder()) continue;
            for (String t : ci.getSupportedTypes()) {
                if (!t.equalsIgnoreCase(mime)) continue;
                try {
                    MediaCodecInfo.VideoCapabilities vc = ci.getCapabilitiesForType(t).getVideoCapabilities();
                    if (vc != null && vc.isSizeSupported(w, h)) (ci.isSoftwareOnly() ? soft : hard).add(ci);
                } catch (RuntimeException ignored) {}
            }
        }
        soft.addAll(hard);
        return soft;
    }

    /** Which formats this phone can encode at this size, as "0,1,2" style list of FrvWriter codec numbers. */
    static String available(int w, int h) {
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < FrvWriter.MIME.length; c++) if (!encoders(FrvWriter.MIME[c], w, h).isEmpty()) sb.append(sb.length() > 0 ? "," : "").append(c);
        return sb.toString();
    }

    /**
     * wanted: FrvWriter codec number, or -1 for the best available. fps may be below 1.
     * Throws IllegalStateException with a plain message if no encoder on the phone can do it.
     */
    VideoEncoder(int wanted, int w, int h, double fps, int bitsPerSecond, Sink sink) {
        this.w = w; this.h = h; this.sink = sink;
        declFps = Math.max(1, (int) Math.round(fps));
        int[] order = wanted >= 0 ? new int[]{wanted} : new int[]{FrvWriter.AV1, FrvWriter.HEVC, FrvWriter.AVC};
        MediaCodec made = null; int madeCodec = -1, madeRate = 0; String why = "no encoder for this picture size";
        for (int c : order) {
            for (MediaCodecInfo ci : encoders(FrvWriter.MIME[c], w, h)) {
                MediaCodec m = null;
                try {
                    MediaCodecInfo.CodecCapabilities caps = ci.getCapabilitiesForType(FrvWriter.MIME[c]);
                    // Encoders budget bits per declared frame. Frames really arrive at `fps`, so scale the request to land on the target.
                    Range<Integer> br = caps.getVideoCapabilities().getBitrateRange();
                    int rate = br.clamp((int) Math.round(bitsPerSecond * declFps / fps));
                    MediaFormat f = MediaFormat.createVideoFormat(FrvWriter.MIME[c], w, h);
                    f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
                    f.setInteger(MediaFormat.KEY_BIT_RATE, rate);
                    f.setInteger(MediaFormat.KEY_FRAME_RATE, declFps);
                    f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10);
                    f.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
                    f.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_NTSC);
                    f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
                    if (caps.getEncoderCapabilities().isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR))
                        f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
                    m = MediaCodec.createByCodecName(ci.getName());
                    m.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    m.start();
                    made = m; madeCodec = c; madeRate = (int) Math.round(rate * fps / declFps);
                    break;
                } catch (Exception e) {
                    why = ci.getName() + ": " + e.getMessage();
                    if (m != null) try { m.release(); } catch (Exception ignored) {}
                }
            }
            if (made != null) break;
        }
        if (made == null) throw new IllegalStateException("The video encoder did not start (" + why + ")");
        mc = made; codec = madeCodec; bitrate = madeRate; name = made.getName();
    }

    /** Encodes one frame. timeMs is the real capture time written to the file. */
    void encode(byte[] i420, long timeMs) throws java.io.IOException {
        int in = mc.dequeueInputBuffer(200_000);
        if (in >= 0) {
            Image img = mc.getInputImage(in);
            if (img == null) throw new java.io.IOException("the encoder gave no input picture");
            fill(img, i420);
            long pts = frameNo++ * 1_000_000L / declFps;
            realTime.put(pts, timeMs);
            mc.queueInputBuffer(in, 0, w * h * 3 / 2, pts, 0);
        } // else the encoder is busy: this frame is dropped
        drain(false);
    }

    /** Copies I420 into the encoder's own layout, whatever strides it uses. */
    private void fill(Image img, byte[] i420) {
        Image.Plane[] pl = img.getPlanes();
        int[] off = {0, w * h, w * h + w * h / 4};
        for (int p = 0; p < 3; p++) {
            int pw = p == 0 ? w : w / 2, ph = p == 0 ? h : h / 2, rs = pl[p].getRowStride(), ps = pl[p].getPixelStride();
            ByteBuffer b = pl[p].getBuffer();
            if (ps == 1) {
                for (int y = 0; y < ph; y++) { b.position(y * rs); b.put(i420, off[p] + y * pw, pw); }
            } else {
                for (int y = 0; y < ph; y++) for (int x = 0; x < pw; x++) b.put(y * rs + x * ps, i420[off[p] + y * pw + x]);
            }
        }
    }

    private void drain(boolean toEnd) throws java.io.IOException {
        for (int idle = 0; ; ) {
            int out = mc.dequeueOutputBuffer(info, toEnd ? 100_000 : 0);
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { sendConfig(mc.getOutputFormat()); continue; }
            if (out == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!toEnd || ++idle > 20) return; continue; }
            if (out < 0) continue;
            idle = 0;
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                if (!configSent) sendConfig(mc.getOutputFormat());
                byte[] d = new byte[info.size];
                ByteBuffer b = mc.getOutputBuffer(out); b.position(info.offset); b.get(d);
                Long t = realTime.remove(info.presentationTimeUs);
                sink.video((info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 ? FrvWriter.KEY : FrvWriter.DELTA, t == null ? info.presentationTimeUs / 1000 : t, d);
            }
            boolean end = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            mc.releaseOutputBuffer(out, false);
            if (end) return;
        }
    }

    private void sendConfig(MediaFormat f) throws java.io.IOException {
        if (configSent) return;
        configSent = true;
        List<byte[]> csd = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ByteBuffer b = f.getByteBuffer("csd-" + i);
            if (b == null) break;
            byte[] d = new byte[b.remaining()]; b.duplicate().get(d); csd.add(d);
        }
        sink.video(FrvWriter.CONFIG, 0, FrvWriter.packConfig(csd.toArray(new byte[0][])));
    }

    /** Asks the encoder to make the next picture a full one. Some encoders ignore this; the next regular key picture then comes as usual. */
    void requestKeyFrame() {
        try {
            android.os.Bundle b = new android.os.Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            mc.setParameters(b);
        } catch (RuntimeException ignored) {}
    }

    /** Flushes the frames still inside the encoder and frees it. */
    void close() {
        try {
            int in = mc.dequeueInputBuffer(200_000);
            if (in >= 0) { mc.queueInputBuffer(in, 0, 0, frameNo * 1_000_000L / declFps, MediaCodec.BUFFER_FLAG_END_OF_STREAM); drain(true); }
        } catch (Exception ignored) {
        } finally {
            try { mc.stop(); } catch (Exception ignored) {}
            mc.release();
        }
    }
}
