package pl.dyktafon;

/**
 * The voice path before the coder: low cut, denoise, treble boost, level control and voice detection. Used for recording, file compression and live voice.
 * Written at 8 kHz for Codec 2 in 2.4 and confirmed on the owner's phone there. At another sample rate every filter keeps its corner frequency and
 * every smoother its time constant, so at 8000 the numbers are those of 2.4.
 * Levels scale with the ceiling: the 2.4 chain for Codec 2 used 11000 because the Codec 2 decoder overshoots peaks 2 to 2.4 times.
 * The DRED decoder overshoots less: over 80 test recordings (tests/dred/chain.py) the decoded peak was at most 1.26 times the
 * ceiling at 18000, while at 20000 and 22000 one onset reached 1.5 times and hit full scale. So recordings use 18000.
 */
final class VoiceChain {
    /** Limiter ceilings: Codec 2 through the modem (live voice), and DRED recordings. */
    static final double CEILING_C2 = 11000, CEILING_DRED = 18000;
    /** At the Codec 2 ceiling; scaled with the ceiling. */
    private static final double AUTO_PEAK_C2 = 8000, MAX_AUTO_GAIN_C2 = 40;

    final Denoiser den; final Vad vad; final double ceiling;
    private final int n, fs; private final double[] x; private final double trebleK, shelfA, scale, autoPeak, maxAutoGain;
    private final double hb0, hb1, hb2, ha1, ha2, hfA, maxLift;
    private final double gateUp, gateDown, envUp, envDown, floorDown, floorRise, ratioRate, liftUp, liftDown;
    private double z1, z2, lpHf, envH, envL, floorH = -1, liftG, avgRatio = -1;
    private double shelf, rmsFloor = -1, gain = 4;
    double level, rawLevel, meter, currentGain, peakOut;
    boolean inputClipped, frameClipped;
    private final double gateFloor; private final int gateFrames; private int gateHold; private double gateG = 1;
    private final Compressor comp;
    private final Limiter limiter;

    /** A smoothing step tuned per sample at 8 kHz, for this sample rate. */
    private double per(double a8) { return 1 - Math.pow(1 - a8, 8000.0 / fs); }

    VoiceChain(int fs, double ceiling, int frameSamples, boolean denoise, double floor, int treble, double[] noiseProfile, int rumbleHz, double liftDb, double gateDb, double compRatio) {
        this.fs = fs; this.ceiling = ceiling; scale = ceiling / CEILING_C2; autoPeak = AUTO_PEAK_C2 * scale; maxAutoGain = MAX_AUTO_GAIN_C2 * scale;
        gain = 4 * scale;
        comp = new Compressor(compRatio, fs, scale);
        limiter = new Limiter(ceiling, 64 * fs / 8000, fs);
        int frameMs = frameSamples * 1000 / fs;
        gateFloor = Math.pow(10, -Math.abs(gateDb) / 20); gateFrames = Math.max(1, 300 / frameMs);
        n = frameSamples; x = new double[n];
        den = new Denoiser(fs, n, noiseProfile); den.reduce = denoise; den.floor = floor;
        vad = new Vad(frameMs);
        trebleK = treble == 0 ? 0 : treble == 1 ? 1.0 : 2.2;
        shelfA = Math.exp(-2 * Math.PI * 1500 / fs);
        // Second-order Butterworth high-pass removes rumble, handling noise and heartbeat-like thumps.
        double w = 2 * Math.PI * Math.max(40, rumbleHz) / fs, q = Math.sqrt(0.5), al = Math.sin(w) / (2 * q), c = Math.cos(w), a0 = 1 + al;
        hb0 = (1 + c) / 2 / a0; hb1 = -(1 + c) / a0; hb2 = hb0; ha1 = -2 * c / a0; ha2 = (1 - al) / a0;
        hfA = Math.exp(-2 * Math.PI * 2000 / fs);
        maxLift = Math.pow(10, liftDb / 20) - 1;
        gateUp = per(0.012); gateDown = per(0.0012); envUp = per(0.3); envDown = per(0.015); floorDown = per(0.01);
        floorRise = Math.pow(1.00002, 8000.0 / fs); ratioRate = per(0.00008); liftUp = per(0.02); liftDown = per(0.003);
    }

    /** Processes one frame in place. manualDb of -100 means automatic level. Returns true while voice is present. */
    boolean process(short[] pcm, int manualDb, int sensitivity, double hold) {
        double sum = 0, rawPk = 1; for (short v0 : pcm) rawPk = Math.max(rawPk, Math.abs((int) v0));
        frameClipped = rawPk >= 32000; rawLevel = Math.max(0, Math.min(1, 1 + 20 * Math.log10(rawPk / 32767) / 40));
        for (int i = 0; i < n; i++) { if (Math.abs(pcm[i]) >= 32000) inputClipped = true; double v = pcm[i]; double y = hb0 * v + z1; z1 = hb1 * v - ha1 * y + z2; z2 = hb2 * v - ha2 * y; x[i] = y; sum += y * y; }
        den.process(x, n);
        den.takeScore();
        boolean voice = vad.update(den.takeBandDb(), sensitivity, hold);
        meter = vad.meter;
        // Noise gate: lowers pauses only. Denoiser delay gives it a head start, and the hold protects word endings.
        if (vad.aboveDb > 6) gateHold = gateFrames; else if (gateHold > 0) gateHold--;
        double gateTarget = gateHold > 0 ? 1 : gateFloor;
        for (int i = 0; i < n; i++) { gateG += (gateTarget > gateG ? gateUp : gateDown) * (gateTarget - gateG); x[i] *= gateG; }
        double rms = Math.sqrt(sum / n), peak = 1;
        for (int i = 0; i < n; i++) { shelf = shelfA * shelf + (1 - shelfA) * x[i]; x[i] += trebleK * (x[i] - shelf); lift(i); double a = Math.abs(x[i]); if (a > peak) peak = a; }
        // Automatic level: drop fast on peaks, rise only when clearly above the background.
        if (rmsFloor < 0) rmsFloor = Math.max(rms, 1);
        else if (rms < rmsFloor) rmsFloor = 0.7 * rmsFloor + 0.3 * Math.max(rms, 1); else rmsFloor *= 1.003;
        double target;
        if (manualDb > -100) target = Math.pow(10, manualDb / 20.0) * scale;
        else {
            // Automatic level: aim well below full scale, fall quickly and rise slowly.
            double want = Math.min(maxAutoGain, autoPeak / peak);
            if (want < gain) target = gain + 0.4 * (want - gain);
            else if (rms > 2 * rmsFloor) target = gain + 0.04 * (want - gain);
            else target = gain;
        }
        currentGain = target / scale; // shown in dB on the page, the same numbers as in 2.4
        double outPk = 0, step = (target - gain) / n;
        for (int i = 0; i < n; i++) {
            gain += step; // ramp within the frame so the level never jumps
            double y = limiter.process(comp.process(x[i] * gain));
            pcm[i] = (short) Math.round(y);
            outPk = Math.max(outPk, Math.abs(y));
        }
        gain = target;
        peakOut = Math.max(peakOut, outPk);
        level = Math.max(0, Math.min(1, 1 + 20 * Math.log10(Math.max(outPk, 1) / ceiling) / 40)); // 100% on the meter is the limiter ceiling
        return voice;
    }

    /** Consonant lift: raises the band above 2 kHz only where it briefly stands out as an s, sh, t or k, not in steady hiss or vowels. */
    private void lift(int i) {
        lpHf = hfA * lpHf + (1 - hfA) * x[i];
        double hf = x[i] - lpHf, ah = Math.abs(hf), al = Math.abs(lpHf);
        envH += (ah > envH ? envUp : envDown) * (ah - envH);
        envL += (al > envL ? envUp : envDown) * (al - envL);
        if (floorH < 0) floorH = envH;
        else if (envH < floorH) floorH += floorDown * (envH - floorH); else floorH *= floorRise;
        // Compare the high/low balance with this voice's own average, so a muffled mic still triggers it.
        double ratio = envH / (envL + 1);
        if (avgRatio < 0) avgRatio = ratio;
        double aboveFloor = envH / (floorH + 1);
        if (aboveFloor > 2) avgRatio += ratioRate * (ratio - avgRatio);
        double dominant = Math.max(0, Math.min(1, (ratio / (avgRatio + 0.005) - 1.2) / 1.0));
        double aboveNoise = Math.max(0, Math.min(1, (aboveFloor - 2) / 2));
        double target = maxLift * dominant * aboveNoise;
        liftG += (target > liftG ? liftUp : liftDown) * (target - liftG);
        x[i] = lpHf + hf * (1 + liftG);
    }
}
