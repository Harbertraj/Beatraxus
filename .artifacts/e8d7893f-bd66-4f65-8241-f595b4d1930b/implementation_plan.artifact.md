# Fix UI Bug in Output Mode Selection

The user reported a UI bug in the audio engine settings where output selection buttons (e.g., AAudio) sometimes lose their "normal background shape" when unselected. Analysis of `SettingsScreen.kt` reveals that the `OutputModeButton` has low contrast with its container and lacks a border in the unselected state. Additionally, the modifier chain for shadows and backgrounds is not following standard Compose practices.

## User Review Required

> [!IMPORTANT]
> I am adding a faint border to the unselected state of the buttons to ensure the rounded shape is always visible, similar to other settings rows in the app. This will change the look of the unselected buttons slightly but will fix the reported "missing shape" issue.

## Proposed Changes

### UI Components

#### [MODIFY] [SettingsScreen.kt](file:///D:/Beatraxus/app/src/main/java/com/beatraxus/app/ui/screens/SettingsScreen.kt)

- Refactor `OutputModeButton` and `PremiumChip` to:
    - Fix the modifier order: `shadow` -> `clip` -> `background` -> `border` -> `clickable`.
    - Apply `shadow` before `background`.
    - Use `Color` instead of `Brush.linearGradient` for solid backgrounds.
    - Add a faint border (`Color.White.copy(0.05f)`) to the unselected state for better visibility against the card surface.
    - Ensure the unselected background has enough contrast with the `CardSurface`.

## Verification Plan

### Automated Tests
- Not applicable for this UI styling change.

### Manual Verification
- Deploy the app and navigate to Settings.
- Verify that the Output Mode buttons (AAudio, MTK HiFi, MMAP) have clearly visible rounded corners in both selected and unselected states.
- Verify that selecting a different mode updates the UI correctly with high-contrast borders and shadows.
- Verify that disabled buttons (e.g., MTK HiFi on unsupported devices) still maintain their shape.
