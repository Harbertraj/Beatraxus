# 5.1 / 7.1 Surround Simulator

Studio DSP page between **EQ** and **MASTER**. Upmixes a stereo/mono song to 5.1 or 7.1.
With the **SURROUND MODE** toggle off, nothing in the audio path changes.

## Where it sits in the audio pipeline

```
decoder -> ring buffer -> renderLoop (AudioEngine)
   -> AudioDspPipeline.process()
        -> NativeDsp chain  (preamp, EQ, tone, spatial, reverb, SoXR resample, limiter, dither)   [unchanged]
        -> SurroundUpmixer  (NEW, last stage, stereo -> 6 / 8 channels, at the output sample rate)
   -> AudioTrackOutput.write()   (AudioTrack opened with CHANNEL_OUT_5POINT1 / 7POINT1_SURROUND)
```

Everything before the upmixer (EQ, reverb, spatial, limiter) therefore still shapes the stereo signal first.

## Who decides the channel count

`resolveSurroundOutputChannels()` (model/SurroundModels.kt) is the single rule. It returns the source's own channel
count unless: the toggle is on, layout is 5.1 / 7.1 / Auto, and none of these apply: Bit-Perfect, A/B bypass, DSD (DoP),
video soundtrack, or an already multichannel source.

* `AudioEngine.configure()` opens the output with that count (`AudioTrackOutput.init(channels)` already supported 6 / 8).
* `AudioEngine.renderLoop()` syncs the pipeline to the channel count the output is really open with, so the two cannot
  disagree. Any batch that would mismatch is dropped for a few ms instead of corrupting the stream.
* Changing the toggle or 5.1 <-> 7.1 re-opens the output (same mechanism as changing sample rate or Bit-Perfect).
  Trims and Movie/Music/Game/Custom only retune the upmixer live.

## Fallbacks

* Device refuses a 6/8 channel track: falls back to the source layout, the page shows STEREO ONLY.
* Device is not truly multichannel (phone speaker, headphones, Bluetooth): Android's mixer folds 5.1/7.1 back to stereo.
  The upmix matrix is built so that fold-down keeps a mono signal at its original level (unit-tested).
  Real discrete 5.1 / 7.1 needs HDMI, a USB DAC or an AV receiver.
* Output rate is capped to 48 kHz on that mixer path.

## Files

| Area | File |
| --- | --- |
| Settings / layout / presets / resolve rule | `model/SurroundModels.kt`, `model/DspModels.kt` |
| Upmix DSP | `engine/SurroundUpmixer.kt` |
| Pipeline + engine + output wiring | `engine/DspPipeline.kt`, `engine/AudioEngine.kt`, `engine/AudioOutput.kt`, `engine/AudioTrackOutput.kt` |
| Persistence | `repository/DspPreferences.kt` (per output device), `repository/AppearancePreferences.kt` |
| UI | `ui/components/dsp/SurroundScreen.kt`, `ui/components/dsp/DspScreen.kt`, `ui/screens/DspPaletteSettings.kt` |
| Tests | `test/.../engine/SurroundUpmixerTest.kt` |
