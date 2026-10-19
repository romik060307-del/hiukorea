# Hiu Korea (Android)

Membungkus web https://koreamasterbyrk1.netlify.app/ menjadi aplikasi Android.
Android 6.0 ke atas (termasuk Oreo 8.0 / 8.1).

## Cara kerja

- **Offline penuh**: seluruh isi web ada di dalam aplikasi. Semua fitur (kamus, 400 soal, flashcards, Otak Korea, dll.) sama persis saat online maupun offline.
- **Online hanya untuk pembaruan**: saat ada internet, aplikasi mengecek Netlify di latar belakang. Kalau kamu sudah deploy versi baru, muncul pesan "Pembaruan tersedia", lalu tekan *Muat sekarang*. Kalau offline, aplikasi diam saja dan tetap jalan normal.
- **Login sekali saja**: setelah masuk, aplikasi langsung terbuka tanpa login. Login muncul lagi hanya kalau menekan *Keluar* (menu ☰ lalu Keluar).
- **Suara Korea**: tombol suara memakai mesin Text-to-speech bawaan Android.
- **Export JSON**: membuka pemilih lokasi simpan Android (tanpa izin tambahan).
- Tombol kembali: menutup menu samping dulu; tekan dua kali untuk keluar.

## Cara membuat APK (GitHub)

1. Upload semua isi folder ini ke repository GitHub (folder `app`, `build.gradle`, `settings.gradle`, `gradle.properties`, `.gitignore`, `README.md`).
2. Buat / ganti file workflow: di GitHub buka `.github/workflows/build-apk.yml` (atau **Add file > Create new file** dengan nama itu), tempel isi file `build-apk.yml` yang dikirim terpisah, lalu **Commit**.
3. Buka tab **Actions**, tunggu build hijau (sekitar 3-6 menit).
4. Ambil `HiuKorea.apk` dari tab **Releases** (langsung .apk) atau dari Actions > run terakhir > artifact **HiuKorea-APK** (ZIP, ekstrak dulu).

## Cara pasang di HP Oreo

1. Izinkan pasang dari sumber luar untuk aplikasi yang dipakai membuka APK (Chrome / File Manager): *Pengaturan > Aplikasi & notifikasi > Akses khusus > Instal aplikasi tidak dikenal*.
2. Buka `HiuKorea.apk`, tekan **Instal**. Kalau ada peringatan Play Protect, pilih **Tetap instal**.
3. **Pasang suara Korea (sekali saja, perlu internet)**: kalau tombol suara memunculkan pesan "Suara Korea belum siap", tekan *Pasang sekarang* lalu pilih **Korea / 한국어**. Setelah itu suara jalan juga saat offline. Pastikan volume media tidak nol.

## Update aplikasi tanpa kehilangan akun

Setiap build memakai kunci tanda tangan yang sama (`app/koreamaster.jks`), jadi APK baru bisa dipasang **menimpa** yang lama tanpa menghapus aplikasi. Akun dan progres belajar tetap ada. **Jangan hapus aplikasi** kalau tidak perlu, karena akun & progres tersimpan di dalam aplikasi. **Jangan ganti file `koreamaster.jks`**.

Untuk update isi web biasa, cukup deploy ke Netlify. Aplikasi mengambilnya sendiri; tidak perlu build APK baru.

Keystore hanya untuk pemasangan manual (sideload), bukan untuk Play Store.

## Mengganti ikon

Ikon ada di `app/src/main/res/mipmap-*` dan `app/src/main/res/drawable-nodpi/ic_launcher_*.png`. Kirim gambar baru ke Claude untuk dibuatkan ulang semua ukurannya.
