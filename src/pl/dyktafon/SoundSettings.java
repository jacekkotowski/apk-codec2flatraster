package pl.dyktafon;

/** The sound settings of Codec2 Recorder, with its defaults. The first group is fixed for a recording; the last three can change while it runs. */
public final class SoundSettings {
    public int source = 6, device = 0, treble = 1, rumble = 180; // 6 is VOICE_RECOGNITION; device 0 is the automatic microphone
    public boolean denoise = true;
    public double floor = 0.18, lift = 6, gate = 12, comp = 3;
    public volatile int gainDb = -100, sensitivity = 5; // -100 is automatic level
    public volatile double hold = 1;

    /** A copy of the fixed group for one recording that shares nothing; the live three are read through `live` while it runs. */
    public SoundSettings copyLive() { SoundSettings s = copy(); s.live = this; return s; }
    private SoundSettings live;
    public int gainDb() { return live != null ? live.gainDb : gainDb; }
    public int sensitivity() { return live != null ? live.sensitivity : sensitivity; }
    public double hold() { return live != null ? live.hold : hold; }

    public SoundSettings copy() {
        SoundSettings s = new SoundSettings();
        s.source = source; s.device = device; s.treble = treble; s.rumble = rumble; s.denoise = denoise; s.floor = floor; s.lift = lift; s.gate = gate; s.comp = comp;
        s.gainDb = gainDb; s.sensitivity = sensitivity; s.hold = hold;
        return s;
    }
}
