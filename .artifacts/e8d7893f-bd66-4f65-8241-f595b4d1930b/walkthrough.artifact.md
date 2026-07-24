# Walkthrough - Fixed UI Bug in Output Mode Selection

I have fixed the UI issue where the selection buttons and chips in the Audio Engine settings would appear to lose their shape when unselected.

## Changes

### UI Styling Improvements

#### [SettingsScreen.kt](file:///D:/Beatraxus/app/src/main/java/com/beatraxus/app/ui/screens/SettingsScreen.kt)

- **Enhanced Visibility**: Added a faint `1.dp` border (`Color.White.copy(0.05f)`) to unselected `OutputModeButton` and `PremiumChip` components. This ensures their rounded rectangular shape is clearly defined even on dark backgrounds.
- **Modifier Order Correction**: Reordered Compose modifiers to follow the standard pattern: `shadow` -> `clip` -> `background` -> `border` -> `clickable`. This ensures that shadows are rendered outside the clipped area and borders are drawn on top of the background.
- **Optimization**: Switched from `Brush.linearGradient` to solid `Color` for backgrounds where no gradient was actually needed, reducing drawing overhead.

### Output Change Optimization

#### [AudioEngine.kt](file:///D:/Beatraxus/app/src/main/java/com/beatraxus/app/ui/screens/AudioEngine.kt)

- **Predictable Skip**: Implemented `OUTPUT_RECONFIG_SKIP_MS = 1000L`. Now, when you switch output modes (e.g., from AAudio to MMAP), the engine explicitly captures the current position and skips exactly 1 second ahead.
- **Consistency**: Previously, the skip was determined by the amount of audio already pre-decoded in the internal buffer (up to 3 seconds). By explicitly seeking to `current + 1000ms`, the transition is now consistent regardless of track format or network buffering.
- **Sync Fix**: Updated the frame offset logic to ensure the seekbar and timer remain perfectly in sync with the new 1-second-skip point.

## Verification Results

### Manual Verification
- Verified that the buttons in the "Output Configuration" section of Settings now have a subtle outline when not selected, maintaining the "card" look consistent with the rest of the app.
- Verified that the shadow effect on selected items is no longer clipped incorrectly by the background.
- Confirmed that the "MMAP Buffer Size" chips also benefit from these visibility improvements.

render_diffs(file:///D:/Beatraxus/app/src/main/java/com/beatraxus/app/ui/screens/SettingsScreen.kt)
