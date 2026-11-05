# SuaraNote

Aplikasi Android (Kotlin + Jetpack Compose) untuk menyusun lagu dari **tuts piano** dan langsung melihat **not balok** dan **not angka**.

## Cara memakai
1. Pilih **panjang nada** (dalam ketuk: 4, 3, 2, 1½, 1, ¾, ½, ¼).
2. Tekan tuts piano (C4 sampai C6, dua oktaf). Nada berbunyi dan langsung masuk ke notasi dengan panjang terpilih.
3. **Rehat** menambah jeda sepanjang panjang terpilih. **Hapus** selalu menghapus satu not/rehat paling akhir. **Putar** memainkan seluruh lagu.
4. Nada dasar, birama, geser oktaf not angka, dan tempo (tombol − dan +) bisa diubah kapan saja; **Metronom** membunyikan tik tiap ketuk (juga saat lagu diputar); notasi langsung dihitung ulang.

## Struktur
- `core/Composition.kt` daftar not/rehat berurutan, tambah/hapus terakhir, pembagian ke birama dan ikatan di garis birama
- `core/Music.kt` ejaan not menurut nada dasar, not angka, teks notasi
- `core/Synth.kt` bunyi sintetis untuk tuts dan pemutaran lagu
- `render/` tata letak not balok dan not angka (Kotlin murni, bisa diuji tanpa Android)
- `audio/AudioPlayer.kt` pemutaran, `ui/` layar Compose (tuts piano di bagian bawah)

## Catatan
- Versi sebelumnya merekam suara (siulan/dengungan) dan menganalisis nadanya; kodenya masih ada di riwayat git.
- Not hanya pada panjang baku (kelipatan 1/16); triol belum didukung.

## Kredit
Bentuk glyph notasi berasal dari font Bravura (c) Steinberg Media Technologies GmbH, SIL Open Font License 1.1.

## Build APK otomatis
Workflow `.github/workflows/build-apk.yml` menjalankan tes unit lalu membuat APK debug pada setiap push ke `main`
(atau manual lewat tab Actions > Build APK > Run workflow). Unduh APK dari bagian **Artifacts** pada run tersebut.
