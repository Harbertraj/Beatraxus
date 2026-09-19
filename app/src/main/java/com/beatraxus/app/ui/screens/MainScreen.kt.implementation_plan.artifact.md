# Implementation Plan - Align Mode Switch Animation with Library Transitions

The user wants to remove the full-screen "transmission effect" when switching between Audio and Video modes and instead use the same subtle animation used when switching between library views (like "All Songs", "Albums", etc.).

## Proposed Changes

### [Component: UI Screens]

#### [MODIFY] [MainScreen.kt](file:///D:/Beatraxus/app/src/main/java/com/beatraxus/app/ui/screens/MainScreen.kt)
- **Remove `modeTransition` `AnimatedContent`**: This wrapper currently causes the entire screen (including the header) to fade and scale when the playback mode changes.
- **Update `viewTransition` `AnimatedContent`**: Change its `targetState` to depend on both `uiState.currentView` and `uiState.playbackMode`. This ensures that changing the mode triggers the same library-style transition within the content area.

```diff
-                    androidx.compose.animation.AnimatedContent(
-                        targetState = uiState.playbackMode,
-                        transitionSpec = {
-                            (fadeIn(animationSpec = tween(220, delayMillis = 80)) + scaleIn(initialScale = 0.98f, animationSpec = tween(220, delayMillis = 80)))
-                                .togetherWith(fadeOut(animationSpec = tween(120)))
-                        },
-                        label = "modeTransition"
-                    ) { mode ->
-                        val _unused = mode
```

```diff
-                                        androidx.compose.animation.AnimatedContent(
-                                            targetState = uiState.currentView,
+                                        androidx.compose.animation.AnimatedContent(
+                                            targetState = uiState.currentView to uiState.playbackMode,
                                             transitionSpec = {
                                                 (fadeIn(animationSpec = tween(220, delayMillis = 80)) + scaleIn(initialScale = 0.98f, animationSpec = tween(220, delayMillis = 80)))
                                                     .togetherWith(fadeOut(animationSpec = tween(120)))
                                             },
                                             label = "viewTransition"
-                                        ) { targetView ->
+                                        ) { (targetView, _) ->
```

## Verification Plan

### Manual Verification
- **Mode Switch**: Switch from Audio to Video mode (and vice versa). Verify that the header remains stable (no fade/scale) while the main content area performs the fade/scale transition.
- **Library Switch**: Navigate between "Home", "All Songs", "Albums", etc. Verify that the animation remains consistent with the new mode switch animation.
- **Search Interaction**: Verify that switching modes while searching still updates results correctly.
