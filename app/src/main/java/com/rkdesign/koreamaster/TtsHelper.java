package com.rkdesign.koreamaster;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.widget.Toast;

import java.util.Locale;

/**
 * Suara Korea untuk tombol suara. WebView Android tidak punya Web Speech API,
 * jadi suara dibunyikan lewat mesin Text-to-Speech milik Android (lewat AppBridge).
 * Mesin dibuat saat pertama kali dibutuhkan agar aplikasi tetap ringan.
 */
final class TtsHelper implements TextToSpeech.OnInitListener {

    private static final int CHUNK = 3500;

    private static final class Req {
        final String text;
        final String lang;
        final double rate;
        final double pitch;

        Req(String text, String lang, double rate, double pitch) {
            this.text = text;
            this.lang = lang;
            this.rate = rate;
            this.pitch = pitch;
        }
    }

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private boolean ready;
    private boolean warned;
    private Req pending;

    TtsHelper(Activity activity) {
        this.activity = activity;
    }

    /** Boleh dipanggil dari thread mana pun. */
    void speak(String text, String lang, double rate, double pitch) {
        if (text == null || text.trim().isEmpty()) return;
        final Req r = new Req(text, lang, rate, pitch);
        main.post(new Runnable() {
            @Override
            public void run() {
                doSpeak(r);
            }
        });
    }

    /** Boleh dipanggil dari thread mana pun. */
    void stop() {
        main.post(new Runnable() {
            @Override
            public void run() {
                pending = null;
                try {
                    if (tts != null && ready) tts.stop();
                } catch (Throwable ignored) {
                    // abaikan
                }
            }
        });
    }

    void shutdown() {
        main.removeCallbacksAndMessages(null);
        try {
            if (tts != null) {
                tts.stop();
                tts.shutdown();
            }
        } catch (Throwable ignored) {
            // abaikan
        }
        tts = null;
        ready = false;
        pending = null;
    }

    private void doSpeak(Req r) {
        if (activity.isFinishing()) return;
        if (tts == null) {
            pending = r;
            try {
                tts = new TextToSpeech(activity.getApplicationContext(), this);
            } catch (Throwable t) {
                tts = null;
                pending = null;
                toast("Suara tidak tersedia di perangkat ini");
            }
            return;
        }
        if (!ready) {
            pending = r; // mesin masih menyala; ucapkan begitu siap
            return;
        }
        speakNow(r);
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) {
            try {
                if (tts != null) tts.shutdown();
            } catch (Throwable ignored) {
                // abaikan
            }
            tts = null;
            ready = false;
            pending = null;
            toast("Mesin suara (Text-to-speech) tidak tersedia. Cek Pengaturan > Bahasa & input > Text-to-speech.");
            return;
        }
        ready = true;
        try {
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                    // tidak dipakai
                }

                @Override
                public void onDone(String utteranceId) {
                    // tidak dipakai
                }

                @Override
                @SuppressWarnings("deprecation")
                public void onError(String utteranceId) {
                    voiceFailed();
                }

                @Override
                public void onError(String utteranceId, int errorCode) {
                    voiceFailed();
                }
            });
        } catch (Throwable ignored) {
            // abaikan
        }
        Req r = pending;
        pending = null;
        if (r != null) speakNow(r);
    }

    private void speakNow(Req r) {
        try {
            Locale loc = toLocale(r.lang);
            int res = tts.setLanguage(loc);
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                askInstallVoice();
                return;
            }
            tts.setSpeechRate((float) clamp(r.rate, 0.3, 2.5));
            tts.setPitch((float) clamp(r.pitch, 0.5, 2.0));

            String text = r.text;
            int len = text.length();
            int mode = TextToSpeech.QUEUE_FLUSH;
            for (int i = 0; i < len; i += CHUNK) {
                String part = text.substring(i, Math.min(len, i + CHUNK));
                tts.speak(part, mode, null, "km" + i);
                mode = TextToSpeech.QUEUE_ADD;
            }
        } catch (Throwable t) {
            toast("Suara gagal diputar");
        }
    }

    private static Locale toLocale(String lang) {
        if (lang == null) return Locale.KOREA;
        String l = lang.trim().toLowerCase(Locale.ROOT);
        if (l.isEmpty() || l.startsWith("ko")) return Locale.KOREA;
        return Locale.forLanguageTag(lang.trim());
    }

    private static double clamp(double v, double lo, double hi) {
        if (v != v) return 1.0; // NaN
        return Math.max(lo, Math.min(hi, v));
    }

    /** Dipanggil dari thread mesin suara: pindah ke thread utama. */
    private void voiceFailed() {
        main.post(new Runnable() {
            @Override
            public void run() {
                askInstallVoice();
            }
        });
    }

    private void askInstallVoice() {
        if (warned || activity.isFinishing()) return;
        warned = true;
        new AlertDialog.Builder(activity)
                .setTitle("Suara Korea belum siap")
                .setMessage("Suara Korea belum bisa diputar. Pasang dulu paket suara bahasa Korea di HP "
                        + "(cukup sekali, perlu internet). Setelah itu suara juga jalan saat offline.")
                .setPositiveButton("Pasang sekarang", (d, w) -> openVoiceInstall())
                .setNegativeButton("Nanti", null)
                .show();
    }

    private void openVoiceInstall() {
        try {
            activity.startActivity(new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA));
            return;
        } catch (Throwable ignored) {
            // coba cara lain
        }
        try {
            activity.startActivity(new Intent("com.android.settings.TTS_SETTINGS"));
        } catch (Throwable t) {
            toast("Buka Pengaturan > Bahasa & input > Text-to-speech, lalu pasang data suara Korea.");
        }
    }

    private void toast(String msg) {
        try {
            Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
            // abaikan
        }
    }
}
