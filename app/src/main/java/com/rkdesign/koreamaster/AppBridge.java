package com.rkdesign.koreamaster;

import android.util.Base64;
import android.webkit.JavascriptInterface;

/**
 * Objek yang bisa dipanggil dari JavaScript halaman web sebagai window.KMApp
 * (lihat assets/bridge.js).
 */
public final class AppBridge {

    private final MainActivity activity;
    private final TtsHelper tts;

    AppBridge(MainActivity activity, TtsHelper tts) {
        this.activity = activity;
        this.tts = tts;
    }

    @JavascriptInterface
    public void speak(String text, String lang, double rate, double pitch) {
        tts.speak(text, lang, rate, pitch);
    }

    @JavascriptInterface
    public void stopSpeak() {
        tts.stop();
    }

    @JavascriptInterface
    public void toast(String message) {
        activity.toast(message);
    }

    @JavascriptInterface
    public void saveFile(String name, String mime, String base64) {
        try {
            byte[] data = Base64.decode(base64 == null ? "" : base64, Base64.DEFAULT);
            activity.requestSave(name, mime, data);
        } catch (Throwable t) {
            activity.toast("Gagal menyimpan file");
        }
    }
}
