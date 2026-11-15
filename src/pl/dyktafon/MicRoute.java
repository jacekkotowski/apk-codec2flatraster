package pl.dyktafon;

import android.os.Build;

/** Microphone choice and Bluetooth routing, copied from Codec2 Recorder 2.7 (RecService and MainActivity). */
public final class MicRoute {
    /** Bluetooth mics use Android's call audio path. Returns the device to record from, or null to use the normal route. */
    public static android.media.AudioDeviceInfo route(android.content.Context c, int deviceId) throws InterruptedException {
        android.media.AudioManager am = c.getSystemService(android.media.AudioManager.class);
        for (android.media.AudioDeviceInfo d : am.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)) {
            if (d.getId() != deviceId) continue;
            if (!isBluetooth(d)) return d;
            am.setMode(android.media.AudioManager.MODE_IN_COMMUNICATION);
            boolean ok = false;
            try {
                if (Build.VERSION.SDK_INT >= 31) {
                    // Android routes calls by the headset's output side, not the microphone device itself.
                    for (android.media.AudioDeviceInfo out : am.getAvailableCommunicationDevices())
                        if (isBluetooth(out) && (ok = am.setCommunicationDevice(out))) break;
                    for (int w = 0; ok && w < 30; w++) {
                        android.media.AudioDeviceInfo cur = am.getCommunicationDevice();
                        if (cur != null && isBluetooth(cur)) break;
                        Thread.sleep(100);
                    }
                } else { am.startBluetoothSco(); am.setBluetoothScoOn(true); ok = true; }
            } catch (RuntimeException e) { ok = false; }
            if (!ok) am.setMode(android.media.AudioManager.MODE_NORMAL);
            callRouted = ok;
            Thread.sleep(ok ? 800 : 0); // the headset needs a moment to open its microphone link
            return d;
        }
        return null;
    }
    static volatile boolean callRouted;
    public static void unroute(android.content.Context c, android.media.AudioDeviceInfo d) {
        if (d == null || !isBluetooth(d) || !callRouted) return;
        callRouted = false;
        android.media.AudioManager am = c.getSystemService(android.media.AudioManager.class);
        if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice(); else { am.setBluetoothScoOn(false); am.stopBluetoothSco(); }
        am.setMode(android.media.AudioManager.MODE_NORMAL);
    }
    public static boolean isBluetooth(android.media.AudioDeviceInfo d) {
        return d.getType() == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO || (Build.VERSION.SDK_INT >= 31 && d.getType() == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET);
    }

    /** The audio source to open: a Bluetooth microphone on the call route needs VOICE_COMMUNICATION; if the route failed, the chosen source. */
    public static int source(android.media.AudioDeviceInfo target, int chosen) {
        return target != null && isBluetooth(target) && callRouted ? android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION : chosen;
    }

    public static String micName(android.media.AudioDeviceInfo d) {
        int t = d.getType(); String kind;
        if (t == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC) kind = "Phone microphone";
        else if (t == android.media.AudioDeviceInfo.TYPE_USB_DEVICE || t == android.media.AudioDeviceInfo.TYPE_USB_HEADSET) kind = "USB";
        else if (t == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET) kind = "Wired headset";
        else if (isBluetooth(d)) kind = "Bluetooth";
        else return null;
        String addr = d.getAddress() == null ? "" : d.getAddress();
        CharSequence pn = d.getProductName();
        String extra = isBluetooth(d) || addr.isEmpty() ? (t == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC ? "" : String.valueOf(pn == null ? "" : pn)) : addr;
        return kind + (extra.isEmpty() ? "" : " (" + extra + ")") + " #" + d.getId();
    }

    /** The inputs the user can choose from, as JSON. */
    public static String list(android.content.Context c) {
        StringBuilder s = new StringBuilder("[");
        for (android.media.AudioDeviceInfo d : c.getSystemService(android.media.AudioManager.class).getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)) {
            String nm = micName(d); if (nm == null) continue;
            if (s.length() > 1) s.append(',');
            s.append("{\"id\":").append(d.getId()).append(",\"name\":").append(org.json.JSONObject.quote(nm)).append('}');
        }
        return s.append(']').toString();
    }
}
