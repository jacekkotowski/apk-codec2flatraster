package pl.flatraster;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import pl.dyktafon.VoiceBridge;

/** Turns a .frv into an ordinary .mp4: the video is copied as it is, the DRED voice is decoded and stored as AAC at 16 kHz. */
final class Mp4Export {
    private static final class Sample { byte[] data; long us; int flags; }

    static void export(File frv, File mp4) throws Exception {
        FrvReader r = new FrvReader(frv);
        if (r.codec == FrvWriter.EXACT) throw new java.io.IOException("SAVE MP4 is not available for Exact recordings yet; they play in this app");
        List<FrvReader.Packet> packets = Player.ordered(r);
        boolean hasVideo = false, hasAudio = false;
        for (FrvReader.Packet p : packets) { hasVideo |= p.type == FrvWriter.KEY; hasAudio |= p.type == FrvWriter.AUDIO; }
        if (!hasVideo && !hasAudio) throw new java.io.IOException("the recording is empty");

        List<Sample> aac = new ArrayList<>();
        MediaFormat audioFormat = hasAudio ? encodeAac(r, packets, aac) : null;

        MediaMuxer mux = new MediaMuxer(mp4.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        try {
            int vt = -1, at = -1;
            if (hasVideo) {
                try { vt = mux.addTrack(Player.videoFormat(r)); }
                catch (RuntimeException e) { throw new java.io.IOException("this phone can't store " + FrvWriter.CODEC_NAME[r.codec] + " video in an .mp4 file"); }
            }
            if (audioFormat != null) at = mux.addTrack(audioFormat);
            mux.start();
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            int ai = 0; boolean gotKey = false; long lastUs = -1;
            for (FrvReader.Packet p : packets) {
                if (p.type != FrvWriter.KEY && p.type != FrvWriter.DELTA) continue;
                if (p.type == FrvWriter.KEY) gotKey = true;
                if (!gotKey) continue;
                long us = Math.max(p.timeMs * 1000, lastUs + 1); lastUs = us; // times must rise
                for (; at >= 0 && ai < aac.size() && aac.get(ai).us <= us; ai++) write(mux, at, aac.get(ai), bi);
                bi.set(0, p.data.length, us, p.type == FrvWriter.KEY ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                mux.writeSampleData(vt, ByteBuffer.wrap(p.data), bi);
            }
            for (; at >= 0 && ai < aac.size(); ai++) write(mux, at, aac.get(ai), bi);
            mux.stop();
        } finally { mux.release(); }
    }

    private static void write(MediaMuxer mux, int track, Sample s, MediaCodec.BufferInfo bi) {
        bi.set(0, s.data.length, s.us, s.flags);
        mux.writeSampleData(track, ByteBuffer.wrap(s.data), bi);
    }

    /** Decodes the voice, fills gaps with silence so it stays in step with the picture, and encodes AAC. Returns the track format. */
    private static MediaFormat encodeAac(FrvReader r, List<FrvReader.Packet> packets, List<Sample> out) throws Exception {
        VoiceBridge.Decoder vb = new VoiceBridge.Decoder();
        MediaCodec enc = MediaCodec.createEncoderByType("audio/mp4a-latm");
        MediaFormat result = null;
        try {
            MediaFormat f = MediaFormat.createAudioFormat("audio/mp4a-latm", AudioPlan.FS, 1); // the phone's AAC encoder took 8 kHz in 0.12; 16 kHz is a standard AAC rate too
            f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            f.setInteger(MediaFormat.KEY_BIT_RATE, 24000);
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();
            // All PCM first: recordings are short and 16 kHz mono is about 2 MB per minute. Gaps are silence, as the player plays them.
            java.io.ByteArrayOutputStream pcmOut = new java.io.ByteArrayOutputStream();
            byte[] le = new byte[AudioPlan.BLOCK * 2];
            for (AudioPlan.Item it : AudioPlan.of(packets)) {
                for (long left = it.silence * 2; left > 0; ) { int k = (int) Math.min(le.length, left); pcmOut.write(new byte[k]); left -= k; }
                int m = Math.min(it.samples, vb.block(it.block, it.block.length));
                ByteBuffer.wrap(le).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(vb.pcm, 0, m);
                pcmOut.write(le, 0, m * 2);
            }
            byte[] all = pcmOut.toByteArray();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int pos = 0; boolean inDone = false, outDone = false;
            for (int idle = 0; !outDone && idle < 200; ) {
                if (!inDone) {
                    int in = enc.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        ByteBuffer b = enc.getInputBuffer(in); b.clear();
                        int n = Math.min(b.remaining() & ~1, all.length - pos);
                        b.put(all, pos, n);
                        long us = (pos / 2) * 1_000_000L / AudioPlan.FS;
                        pos += n; inDone = pos >= all.length;
                        enc.queueInputBuffer(in, 0, n, us, inDone ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                    }
                }
                int o = enc.dequeueOutputBuffer(info, 10_000);
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { result = enc.getOutputFormat(); continue; }
                if (o < 0) { idle++; continue; }
                idle = 0;
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                    Sample s = new Sample(); s.data = new byte[info.size]; s.us = info.presentationTimeUs; s.flags = info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME;
                    ByteBuffer b = enc.getOutputBuffer(o); b.position(info.offset); b.get(s.data);
                    if (out.isEmpty() || s.us > out.get(out.size() - 1).us) out.add(s);
                }
                outDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                enc.releaseOutputBuffer(o, false);
            }
            if (result == null) throw new java.io.IOException("the sound encoder gave no output");
            return result;
        } finally {
            try { enc.stop(); } catch (Exception ignored) {}
            enc.release(); vb.close();
        }
    }
}
