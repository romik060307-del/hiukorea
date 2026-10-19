package com.rkdesign.koreamaster;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Menyimpan halaman web Hiu Korea di dalam aplikasi supaya bisa dipakai penuh tanpa internet.
 *
 * - Bawaan: assets/index.html (ikut di dalam APK).
 * - Saat online: mengecek https://koreamasterbyrk1.netlify.app/ ; kalau isinya berbeda,
 *   versi baru disimpan di penyimpanan internal dan dipakai menggantikan versi bawaan.
 * - Versi baru hanya disimpan kalau terlihat valid, jadi halaman error / jaringan wifi
 *   berbayar (captive portal) tidak akan menimpa versi yang sehat.
 */
final class SiteStore {

    static final String SITE_URL = "https://koreamasterbyrk1.netlify.app/";
    static final String SITE_HOST = "koreamasterbyrk1.netlify.app";

    static final int RESULT_FAILED = -1;   // offline / server error: tidak ada yang berubah
    static final int RESULT_SAME = 0;      // sudah versi terbaru
    static final int RESULT_UPDATED = 1;   // versi baru tersimpan

    private static final int MIN_BYTES = 50000;
    private static final Pattern HEAD_TAG = Pattern.compile("<head(\\s[^>]*)?>", Pattern.CASE_INSENSITIVE);

    private final Context ctx;
    private final File dir;
    private final File cacheFile;
    private final SharedPreferences prefs;
    private String bridgeJs;

    SiteStore(Context context) {
        ctx = context.getApplicationContext();
        dir = new File(ctx.getFilesDir(), "site");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        cacheFile = new File(dir, "index.html");
        prefs = ctx.getSharedPreferences("site_store", Context.MODE_PRIVATE);

        // Kalau HTML bawaan APK berubah (APK baru dengan isi web baru), buang salinan lama
        // lalu cek ulang ke Netlify. Mencegah salinan lama menimpa yang lebih baru.
        try {
            String bundled = sha256(readAsset("index.html"));
            if (!bundled.equals(prefs.getString("bundled_hash", null))) {
                //noinspection ResultOfMethodCallIgnored
                cacheFile.delete();
                prefs.edit().remove("etag").putString("bundled_hash", bundled).apply();
            }
        } catch (Throwable ignored) {
            // tidak fatal
        }
    }

    /** Halaman lengkap (dengan jembatan disisipkan) siap disajikan ke WebView. */
    byte[] buildPage() {
        byte[] raw = readCurrent();
        String html = new String(raw, StandardCharsets.UTF_8);
        String inject = "<script>" + bridge() + "</script>";
        Matcher m = HEAD_TAG.matcher(html);
        String out;
        if (m.find()) {
            int at = m.end();
            out = html.substring(0, at) + inject + html.substring(at);
        } else {
            out = inject + html;
        }
        return out.getBytes(StandardCharsets.UTF_8);
    }

    /** Isi halaman yang sedang dipakai: salinan terbaru dari Netlify kalau ada, kalau tidak yang bawaan. */
    private byte[] readCurrent() {
        try {
            if (cacheFile.isFile() && cacheFile.length() >= MIN_BYTES) {
                return readFile(cacheFile);
            }
        } catch (Throwable ignored) {
            // lanjut ke versi bawaan
        }
        try {
            return readAsset("index.html");
        } catch (Throwable ignored) {
            // lanjut ke halaman darurat
        }
        return "<!DOCTYPE html><html><body style=\"background:#07111f;color:#fff;font-family:sans-serif;padding:24px\"><h2>Hiu Korea</h2><p>Data aplikasi tidak bisa dibaca. Silakan pasang ulang aplikasi.</p></body></html>"
                .getBytes(StandardCharsets.UTF_8);
    }

    private synchronized String bridge() {
        if (bridgeJs == null) {
            try {
                bridgeJs = new String(readAsset("bridge.js"), StandardCharsets.UTF_8);
            } catch (Throwable t) {
                bridgeJs = "";
            }
        }
        return bridgeJs;
    }

    /**
     * Cek Netlify. Dipanggil dari thread latar belakang (jangan dari thread utama).
     */
    synchronized int checkForUpdate() {
        HttpURLConnection c = null;
        try {
            URL url = new URL(SITE_URL);
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(20000);
            c.setRequestProperty("Cache-Control", "no-cache");
            c.setRequestProperty("User-Agent", "KoreaMasterApp/1.0");
            String etag = prefs.getString("etag", null);
            if (etag != null) {
                c.setRequestProperty("If-None-Match", etag);
            }

            int code = c.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                return RESULT_SAME;
            }
            if (code != HttpURLConnection.HTTP_OK) {
                return RESULT_FAILED;
            }

            byte[] body = readAll(c.getInputStream());
            if (!looksValid(body)) {
                return RESULT_FAILED;
            }
            String newEtag = c.getHeaderField("ETag");

            if (sha256(body).equals(sha256(readCurrent()))) {
                // isi sama persis dengan yang sedang dipakai
                if (newEtag != null) prefs.edit().putString("etag", newEtag).apply();
                return RESULT_SAME;
            }

            File tmp = new File(dir, "index.html.tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(body);
                out.flush();
                out.getFD().sync();
            } finally {
                out.close();
            }
            if (!tmp.renameTo(cacheFile)) {
                //noinspection ResultOfMethodCallIgnored
                cacheFile.delete();
                if (!tmp.renameTo(cacheFile)) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                    return RESULT_FAILED;
                }
            }
            SharedPreferences.Editor ed = prefs.edit();
            if (newEtag != null) ed.putString("etag", newEtag); else ed.remove("etag");
            ed.apply();
            return RESULT_UPDATED;
        } catch (Throwable t) {
            return RESULT_FAILED;
        } finally {
            if (c != null) {
                try { c.disconnect(); } catch (Throwable ignored) { /* abaikan */ }
            }
        }
    }

    private static boolean looksValid(byte[] b) {
        if (b == null || b.length < MIN_BYTES) return false;
        int from = Math.max(0, b.length - 2000);
        String tail = new String(b, from, b.length - from, StandardCharsets.UTF_8).toLowerCase();
        if (!tail.contains("</html>")) return false;
        String head = new String(b, 0, Math.min(b.length, 4000), StandardCharsets.UTF_8).toLowerCase();
        return head.contains("<html") || head.contains("<!doctype");
    }

    private static String sha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(data);
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte x : d) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    private byte[] readAsset(String name) throws Exception {
        InputStream in = ctx.getAssets().open(name);
        return readAll(in);
    }

    private static byte[] readFile(File f) throws Exception {
        return readAll(new FileInputStream(f));
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            try { in.close(); } catch (Throwable ignored) { /* abaikan */ }
        }
    }
}
