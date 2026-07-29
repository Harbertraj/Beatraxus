# Beatraxus

Beatraxus is a high-performance Android music player designed for audiophiles. It features a custom low-latency audio engine built on AudioTrack with integrated resampling and equalization capabilities.

## Screenshots

<p align="center">
  <img src="screenshots/main.png" width="32%" />
  <img src="screenshots/now_playing.png" width="32%" />
  <img src="screenshots/queue.png" width="32%" />
</p>
<p align="center">
  <img src="screenshots/settings.png" width="32%" />
  <img src="screenshots/dsp.png" width="32%" />
  <img src="screenshots/eq.png" width="32%" />
</p>
<p align="center">
  <img src="screenshots/reverb.png" width="32%" />
  <img src="screenshots/mastering.png" width="32%" />
</p>

## Key Features

### 🎧 Pro-Audio Engine
- **Bit-Perfect Mode**: Bypasses the Android audio mixer and all DSP for untouched, bit-perfect audio delivery to your hardware.
- **USB Direct Output**: Support for external USB DACs with direct hardware access, bypassing system limitations.
- **High-Resolution Support**: Native playback support for FLAC, ALAC, WAV, and more at up to 32-bit/384kHz+.
- **64-bit Internal Precision**: High-precision floating point processing throughout the entire DSP chain.

### 🎚️ Advanced DSP Engine
- **Parametric Equalizer**: 10-band high-precision EQ with customizable Q-factors and phase-linear processing.
- **SOXR Resampling**: Industry-standard resampler for ultra-clean sample rate conversion.
- **Dithering**: Multiple dither types (TPDF, Shaped, Highpass) to improve audio precision at lower bit depths.
- **Peak Limiter & Saturation**: Smooth soft-knee protection and musical saturation for fatigue-free listening.
- **Auto-EQ Integration**: Search and apply optimized EQ profiles for over 4,000 headphone models.

### ☁️ Connectivity & Library
- **Cloud Library Sync**: Stream and cache your music directly from **Google Drive**, **Dropbox**, **OneDrive**, **Box**, and **Nextcloud**.
- **Telegram Streaming**: Access and stream audio content from your private and public Telegram channels.
- **NAS Support**: High-performance streaming from **SMB (CIFS)** and **FTP/SFTP** servers.
- **AI Intelligence**: Automated mood and genre analysis using local TFLite models and Metadata enrichment.

### ✨ Modern UI/UX
- **Glass-morphism**: A sleek, dark-centric interface built entirely with Jetpack Compose.
- **Dynamic Themes**: Backgrounds and accents that adapt to your current album art.
- **Gapless Playback**: Seamless transitions between tracks with customizable crossfade.

## Technical Details

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose (Material 3)
- **Audio Engine**: Custom `AudioTrack`-based engine with native C++ decoding.
- **Audio Processing**: Native DSP implementation via Oboe/AAudio.
- **Architecture**: MVVM with StateFlow and Coroutines.
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 36 (Android 15)

## Project Structure

- `:app`: Main application module.
  - `com.beatraxus.app.engine`: Core audio processing and native playback logic.
  - `com.beatraxus.app.drive`: Cloud storage providers and cache management.
  - `com.beatraxus.app.telegram`: TDLib integration for Telegram streaming.
  - `com.beatraxus.app.ui.screens`: Main Compose screens and navigation.
  - `com.beatraxus.app.viewmodel`: Reactive state management and business logic.
  - `com.beatraxus.app.repository`: Data access layer and metadata extraction.

## Build and Run

1. Clone the repository.
2. Open in Android Studio Iguana+ or Ladybug+.
3. Provide your own API keys in `local.properties` (Last.fm, Telegram, etc.).
4. Build and run the `app` module.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
