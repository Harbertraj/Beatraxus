# Beatflowy

Beatflowy is a high-performance Android music player designed for audiophiles. It features a custom audio engine built on Media3 ExoPlayer with integrated resampling and equalization capabilities.

## Features

- **High-Resolution Audio Support**: Custom audio pipeline using `DefaultAudioSink` with float output.
- **Real-time Resampling**: Adjustable resampling to match your output device's capabilities.
- **10-Band Equalizer**: Fine-tune your listening experience.
- **Modern UI**: Built entirely with Jetpack Compose following Material 3 guidelines.
- **Dynamic Theme**: Dark-centric aesthetic with vibrant accents (Red & Blue).
- **Audio Info Bar**: Real-time display of input/output sample rates and device info.

## Technical Details

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose
- **Audio Engine**: AndroidX Media3 (ExoPlayer)
- **Architecture**: MVVM with StateFlow and Coroutines
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 34 (Android 14)

## Getting Started

### Prerequisites

- Android Studio Iguana (2023.2.1) or newer
- JDK 17
- Android device or emulator running SDK 26+

### Build and Run

1. Clone the repository:
   ```bash
   git clone https://github.com/yourusername/Beatflowy.git
   ```
2. Open the project in Android Studio.
3. Wait for Gradle sync to complete.
4. Run the `app` module on your device.

## Project Structure

- `:app`: Main application module.
  - `com.beatflowy.app.engine`: Core audio processing logic (Resampler, Equalizer, AudioEngine).
  - `com.beatflowy.app.ui`: Compose screens, components, and theme.
  - `com.beatflowy.app.viewmodel`: State management for UI and Audio Engine.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
