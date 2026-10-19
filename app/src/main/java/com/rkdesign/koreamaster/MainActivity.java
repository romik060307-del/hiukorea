package com.rkdesign.koreamaster;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Hiu Korea: membungkus web https://koreamasterbyrk1.netlify.app/ menjadi aplikasi.
 *
 * Cara kerja offline:
 *  - Halaman web disimpan di dalam aplikasi (bawaan APK, lalu diperbarui dari Netlify bila ada).
 *  - Setiap permintaan ke alamat Netlify itu dilayani dari penyimpanan lokal, bukan dari internet,
 *    jadi tampilan dan fitur sama persis saat online maupun offline.
 *  - Saat ada internet, aplikasi mengecek Netlify di latar belakang. Kalau ada versi baru,
 *    ditawarkan untuk dimuat. Akun & progres belajar disimpan di penyimpanan web (localStorage)
 *    sehingga tetap aman saat halaman diperbarui.
 */
public class MainActivity extends Activity {

    private static final int BG = 0xFF07111F;
    private static final int REQ_SAVE = 4711;
    private static final long RECHECK_OK_MS = 30L * 60L * 1000L;
    private static final long RECHECK_TRY_MS = 60L * 1000L;

    private final Handler main = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private WebView web;
    private SiteStore store;
    private TtsHelper tts;

    private long lastBackAt;
    private long lastAttemptAt;
    private long lastOkAt;
    private boolean checking;
    private boolean updateDialogShowing;
    private byte[] pendingSave;

    private final Runnable updateRunnable = new Runnable() {
        @Override
        public void run() {
            checkUpdateAsync();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        store = new SiteStore(this);
        tts = new TtsHelper(this);

        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        setContentView(root);

        createWebView();
        loadSite();
    }

    @SuppressWarnings("deprecation")
    private void createWebView() {
        try {
            web = new WebView(this);
        } catch (Throwable t) {
            web = null;
            showFatal("Komponen WebView tidak tersedia di HP ini.\n\n"
                    + "Buka Play Store, cari \"Android System WebView\", lalu pasang atau aktifkan, "
                    + "kemudian buka aplikasi ini lagi.");
            return;
        }

        web.setBackgroundColor(BG);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // tempat akun & progres belajar tersimpan
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        // Isi halaman ada di dalam aplikasi: tidak perlu cek Safe Browsing lewat internet.
        if (Build.VERSION.SDK_INT >= 26) s.setSafeBrowsingEnabled(false);
        // Halaman sudah bertema gelap sendiri; jangan biarkan Android menggelapkan lagi (warna jadi kacau).
        if (Build.VERSION.SDK_INT >= 29 && Build.VERSION.SDK_INT < 33) s.setForceDark(WebSettings.FORCE_DARK_OFF);

        web.setWebViewClient(new AppClient());
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new AppBridge(this, tts), "KMApp");

        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void loadSite() {
        if (web != null) web.loadUrl(SiteStore.SITE_URL);
    }

    private void showFatal(String message) {
        TextView tv = new TextView(this);
        tv.setText(message);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(16f);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(48, 48, 48, 48);
        root.addView(tv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** Dipanggil kalau proses render WebView mati (misalnya kehabisan memori): pulihkan tanpa menutup aplikasi. */
    private void rebuildWebView() {
        if (isFinishing()) return;
        try {
            if (web != null) {
                root.removeView(web);
                web.destroy();
            }
        } catch (Throwable ignored) {
            // abaikan
        }
        web = null;
        createWebView();
        loadSite();
    }

    // ------------------------------------------------------------------ WebViewClient

    private final class AppClient extends WebViewClient {

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            try {
                return serve(request.getUrl());
            } catch (Throwable t) {
                return null;
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleUrl(request.getUrl().toString());
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleUrl(url);
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    rebuildWebView();
                }
            });
            return true;
        }
    }

    /** Alamat Netlify dilayani dari penyimpanan lokal. Alamat lain tidak disentuh. */
    private WebResourceResponse serve(Uri uri) {
        if (uri == null) return null;
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) return null;
        if (!("https".equals(scheme) || "http".equals(scheme))) return null;
        if (!SiteStore.SITE_HOST.equalsIgnoreCase(host)) return null;

        String path = uri.getPath();
        boolean isPage = path == null || path.isEmpty() || "/".equals(path) || "/index.html".equals(path);
        if (isPage) {
            Map<String, String> headers = new HashMap<String, String>();
            headers.put("Cache-Control", "no-store");
            return new WebResourceResponse("text/html", "UTF-8", 200, "OK", headers,
                    new ByteArrayInputStream(store.buildPage()));
        }
        // file lain (mis. favicon) tidak ada: jawab 404 kosong, tanpa menyentuh internet
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                new HashMap<String, String>(), new ByteArrayInputStream(new byte[0]));
    }

    private boolean handleUrl(String url) {
        try {
            Uri u = Uri.parse(url);
            String scheme = u.getScheme();
            if (scheme == null) return false;
            if (("http".equals(scheme) || "https".equals(scheme))
                    && SiteStore.SITE_HOST.equalsIgnoreCase(u.getHost())) {
                return false; // tetap di dalam aplikasi
            }
            if ("about".equals(scheme) || "blob".equals(scheme)
                    || "data".equals(scheme) || "javascript".equals(scheme)) {
                return false;
            }
            startActivity(new Intent(Intent.ACTION_VIEW, u));
            return true;
        } catch (Throwable t) {
            return true;
        }
    }

    // ------------------------------------------------------------------ Pembaruan dari Netlify

    private void checkUpdateAsync() {
        if (checking || isFinishing() || web == null) return;
        long now = SystemClock.elapsedRealtime();
        if (lastAttemptAt != 0 && now - lastAttemptAt < RECHECK_TRY_MS) return;
        if (lastOkAt != 0 && now - lastOkAt < RECHECK_OK_MS) return;
        checking = true;
        lastAttemptAt = now;

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                int result = SiteStore.RESULT_FAILED;
                try {
                    result = store.checkForUpdate();
                } catch (Throwable ignored) {
                    // abaikan
                }
                final int res = result;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        checking = false;
                        if (res != SiteStore.RESULT_FAILED) {
                            lastOkAt = SystemClock.elapsedRealtime();
                        }
                        if (res == SiteStore.RESULT_UPDATED) promptReload();
                    }
                });
            }
        }, "km-update");
        t.setDaemon(true);
        t.start();
    }

    private void promptReload() {
        if (isFinishing() || updateDialogShowing || web == null) return;
        updateDialogShowing = true;
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Pembaruan tersedia")
                .setMessage("Versi terbaru Hiu Korea sudah diunduh dan siap dipakai. "
                        + "Muat sekarang? Akun dan progres belajarmu tetap aman.")
                .setPositiveButton("Muat sekarang", (d, w) -> loadSite())
                .setNegativeButton("Nanti", null)
                .create();
        dlg.setOnDismissListener(d -> updateDialogShowing = false);
        dlg.show();
    }

    // ------------------------------------------------------------------ Simpan file (Export JSON)

    void requestSave(final String name, final String mime, final byte[] data) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) return;
                pendingSave = data;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
                i.putExtra(Intent.EXTRA_TITLE, (name == null || name.isEmpty()) ? "file" : name);
                try {
                    startActivityForResult(i, REQ_SAVE);
                } catch (Throwable t) {
                    pendingSave = null;
                    toast("Tidak bisa membuka pemilih lokasi simpan");
                }
            }
        });
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_SAVE) return;
        byte[] bytes = pendingSave;
        pendingSave = null;
        if (resultCode != RESULT_OK || data == null || data.getData() == null || bytes == null) return;
        OutputStream os = null;
        try {
            os = getContentResolver().openOutputStream(data.getData());
            if (os == null) throw new IllegalStateException("no stream");
            os.write(bytes);
            os.flush();
            toast("File tersimpan");
        } catch (Throwable t) {
            toast("Gagal menyimpan file");
        } finally {
            if (os != null) {
                try { os.close(); } catch (Throwable ignored) { /* abaikan */ }
            }
        }
    }

    void toast(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                    // abaikan
                }
            }
        });
    }

    // ------------------------------------------------------------------ Siklus hidup & tombol kembali

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web == null) {
            super.onBackPressed();
            return;
        }
        // 1) kalau menu samping (mobile) sedang terbuka, tutup dulu
        web.evaluateJavascript(
                "(function(){try{var s=document.getElementById('sidebar');"
                        + "if(s&&s.classList.contains('open')){s.classList.remove('open');return 1;}}"
                        + "catch(e){}return 0;})()",
                value -> {
                    if ("1".equals(value)) return;
                    // 2) tekan dua kali untuk keluar
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastBackAt < 2000) {
                        finish();
                    } else {
                        lastBackAt = now;
                        toast("Tekan sekali lagi untuk keluar");
                    }
                });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        main.removeCallbacks(updateRunnable);
        main.postDelayed(updateRunnable, 2500); // beri waktu halaman tampil dulu
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(updateRunnable);
        tts.stop();
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        tts.shutdown();
        try {
            if (web != null) {
                web.removeJavascriptInterface("KMApp");
                root.removeView(web);
                web.destroy();
            }
        } catch (Throwable ignored) {
            // abaikan
        }
        web = null;
        super.onDestroy();
    }
}
