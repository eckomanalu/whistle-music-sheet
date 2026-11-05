# SuaraNote

Aplikasi Android (Kotlin + Jetpack Compose) untuk menyusun lagu dari **tuts piano** dan langsung melihat **not balok** dan **not angka**.

## Cara memakai
1. Pilih **panjang nada** (dalam ketuk: 4, 3, 2, 1½, 1, ¾, ½, ¼).
2. Tekan tuts piano (C4 sampai C6, dua oktaf). Nada berbunyi dan langsung masuk ke notasi dengan panjang terpilih.
3. **0 Rehat** menambah jeda sepanjang panjang terpilih. **• Sambung** menambah perpanjangan (titik) sebesar panjang terpilih pada not terakhir. **Hapus** selalu menghapus satu *simbol* paling akhir: kepala not, rehat, atau satu titik perpanjangan (bukan seluruh not). **Putar** memainkan lagu; simbol yang sedang berbunyi disorot (oranye) di not angka dan not balok.
   Sentuh sebuah not, atau ruang kosong dalam sebuah birama, untuk memilih awal pemutaran (biru); tombol berubah menjadi **Dari sini**. Sentuh lagi untuk membatalkan.
4. **Mode live:** nyalakan chip *Mode live*, lalu tahan tuts selama durasi yang diinginkan. Not langsung muncul dan memanjang selama ditahan; saat dilepas panjangnya dibulatkan ke ketukan (pembulatan ¼, ½, atau 1 ketuk). Jeda antar-tuts yang cukup panjang menjadi rehat otomatis, kecuali chip *Legato* dinyalakan (nada selalu bersambung). Nyalakan Metronom agar ketukan stabil.
5. Nada dasar, birama, geser oktaf not angka, dan tempo (tombol − dan +) bisa diubah kapan saja; **Metronom** membunyikan tik tiap ketuk (juga saat lagu diputar); notasi langsung dihitung ulang.

## Not angka
- Angka 1..7 dengan titik oktaf; garis **di atas** angka: 1/8 satu garis, 1/16 dua garis.
- Perpanjangan nada digambar sebagai titik: 1 ketuk = titik, 1/2 ketuk = titik dengan satu garis di atas, 1/4 ketuk = titik dengan dua garis di atas.
- Kres: angka dicoret garis miring `/` menempel pada angkanya; mol: dicoret `\`.
- Rehat `0`.

## Struktur
- `core/Composition.kt` lagu sebagai deretan simbol (kepala not + perpanjangan), hapus per simbol, pembagian ke birama, ikatan, dan simbol not angka
- `core/LiveTiming.kt` pembulatan waktu mode live
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

## Multi-suara, sisip, nomor bar, blok salin-tempel

- **Suara**: sampai 4 suara (chip "Suara 1…4", "+ Suara", "− Hapus suara"). Semua suara tampil bertumpuk dengan barline sejajar dan terdengar bersamaan saat diputar.
- **Titik pilih**: ketuk not/bar untuk memilih titik. Not baru, rehat, sambung, dan hapus bekerja di titik itu (menyisip, not setelahnya bergeser). ▶ "Dari sini" memutar dari titik itu.
- **Nomor bar** tampil di not balok dan not angka.
- **Blok bar**: nyalakan "Blok bar", ketuk bar awal dan akhir, lalu **Salin**. Pilih bar tujuan lalu **Tempel: sisip** (menggeser isi) atau **Tempel: timpa** (menghapus not di bar tujuan). **Salin ke akhir** menempelkan blok ke bar terakhir.

## Dropdown, menu bar, suku kata

- **Nada dasar**, **Birama**, dan **Suara (lapisan)** berupa dropdown. Menambah/menghapus suara ada di dropdown Suara.
- **Tekan lama sebuah bar** untuk menu: mulai seleksi, pilih akhir seleksi, salin, hapus bar terseleksi, tempel (sisip/timpa) di bar itu, salin ke bar terakhir, batalkan seleksi. Aksi berlaku pada suara bar yang ditekan.
- **Suku kata**: isi "Suku kata aktif", lalu sentuh area di bawah angka pada not angka. Suku kata menempel pada not (ikut bergeser, tersalin, dan terhapus bersama not). Teks kosong + sentuh = hapus suku kata.
