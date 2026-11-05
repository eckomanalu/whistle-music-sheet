# SuaraNote

Aplikasi Android (Kotlin + Jetpack Compose) yang mendengarkan nyanyian atau siulan lewat mikrofon, lalu menampilkan **not balok** dan **not angka**.

## Cara menjalankan
1. Buka folder ini di Android Studio (versi terbaru), tunggu Gradle sync (jika diminta, setujui upgrade AGP/Kotlin).
2. Jalankan di HP asli (mikrofon emulator kurang andal), izinkan akses mikrofon.
3. Tekan **Rekam**, nyanyikan satu nada pada satu waktu, tekan **Berhenti**.

Tempo, birama, nada dasar, dan geser oktaf bisa diubah setelah merekam; notasi dihitung ulang tanpa merekam lagi.

## Struktur
- `core/PitchDetector.kt` deteksi nada (algoritma YIN)
- `core/Transcriber.kt` nada -> not -> birama (segmentasi, kuantisasi, ikatan)
- `core/Music.kt` ejaan not menurut nada dasar, not angka, teks notasi
- `render/` tata letak not balok dan not angka (Kotlin murni, bisa diuji tanpa Android)
- `audio/PitchRecorder.kt` perekaman mikrofon
- `ui/` layar Compose

## Batasan
- Hanya monofonik (satu nada sekaligus); piano/gitar berakor tidak didukung.
- Tempo diatur manual; kuantisasi ke 1/16.
- Tes unit memakai suara sintetis, belum rekaman mikrofon nyata.

## Kredit
Bentuk glyph notasi berasal dari font Bravura (c) Steinberg Media Technologies GmbH, SIL Open Font License 1.1.

## Build APK otomatis
Workflow `.github/workflows/build-apk.yml` menjalankan tes unit lalu membuat APK debug pada setiap push ke `main`
(atau manual lewat tab Actions > Build APK > Run workflow). Unduh APK dari bagian **Artifacts** pada run tersebut
(`SuaraNote-debug-apk`) dan pasang di HP (aktifkan "pasang dari sumber tidak dikenal").
