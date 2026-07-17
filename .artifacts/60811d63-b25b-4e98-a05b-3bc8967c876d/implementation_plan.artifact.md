# Fix 1-second gap on song auto-advance

The goal is to eliminate the audible gap (~1s) between songs during auto-advance. This gap is caused by:
1.  An artificial 150ms delay in `performTrackChange` meant for debouncing manual skips, but applied to auto-advance too.
2.  The engine falling back to a manual track change if the next track's preload hasn't finished probing its format yet.

## Proposed Changes

### [AudioPlaybackService](file:///D:/Beatraxus/app/src/main/java/com/beatraxus/app/service/AudioPlaybackService.kt)

#### [MODIFY] [AudioPlaybackService.kt](file:///D:/Beatraxus/app/src/main/java/com/beatraxus/app/service/AudioPlaybackService.kt)
- Update `performTrackChange(delta: Int)` to accept an optional `isAutoAdvance: Boolean = false` parameter.
- Wrap the 150ms `delay` in a check for `!isAutoAdvance`.
- Update `next()` and `previous()` to accept `isAutoAdvance`.
- Update `handleCompletion()` to pass `isAutoAdvance = true` when calling `next()`.

### [AudioEngine](file:///D:/Beatraxus/app/src/main/java/com/beatraxus/app/engine/AudioEngine.kt)

#### [MODIFY] [AudioEngine.kt](file:///D:/Beatraxus/app/src/main/java/com/beatraxus/app/engine/AudioEngine.kt)
- In `renderLoop`, allow promoting `nextSession` to `activeSession` even if its `pcmFormat` is still null (probing).
- Ensure the engine waits for the format to be identified before processing audio, rather than giving up and signaling completion.
- Ensure `setStartFrameOffset` is still called correctly during this "early" promotion.

## Verification Plan

### Automated Tests
- N/A (UI and Timing sensitive)

### Manual Verification
- Play a short track and verify it transitions to the next track without an audible gap.
- Test with slow cloud sources (Google Drive/Telegram) to ensure the fallback path (now without the 150ms delay) still works smoothly.
- Rapidly tap the "Next" button to ensure the 150ms debounce still works for manual interactions.
- Verify that Repeat Mode (ONE, ALL) still works as expected.
