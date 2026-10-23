# Video Compressor (Android)

Pick one or more videos, choose a quality, tap Compress. Compressed copies are saved to
`Movies/Compressed` on your phone. Originals are never touched.

- Android 10 or newer
- Uses Android's Media3 Transformer (hardware-accelerated H.265 / H.264)
- No ads, no internet permission, no storage permission needed

## Get the APK (no coding, about 5 minutes)

1. Create a free account at github.com and make a **new private repository**.
2. Click **Add file > Upload files**, drag in everything from this folder
   (including the hidden `.github` folder), and commit.
3. Open the **Actions** tab, choose **Build APK**, then **Run workflow**.
4. After 3-6 minutes, open the finished run and download **VideoCompressor-APK**
   (a zip containing `app-debug.apk`).
5. Copy the APK to your phone, tap it, and allow "Install unknown apps" when asked.

## Or build with Android Studio

Open this folder in Android Studio, let it sync, then **Build > Build APK(s)**.

## Notes

- Keep the app open while compressing (the screen stays on automatically).
- H.265 gives the smallest files. Untick it if you need maximum compatibility.
- Quality presets never upscale a video and never raise its bitrate.
