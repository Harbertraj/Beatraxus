# Fix Compilation Errors in AudioSpectrumAnalyzerTest

The unit tests for `AudioSpectrumAnalyzer` are failing to compile because they reference two methods, `detectSpectralCutoff` and `isSuspiciousCutoff`, which are no longer present (or were renamed/moved) in the `AudioSpectrumAnalyzer` class.

## Proposed Changes

### Audio Engine

#### [MODIFY] [AudioSpectrumAnalyzer.kt](file:///D:/Beatraxus/app/src/main/java/com/beatraxus/app/engine/AudioSpectrumAnalyzer.kt)

- Restore `detectSpectralCutoff` and `isSuspiciousCutoff` to the `companion object` of `AudioSpectrumAnalyzer`.
- `detectSpectralCutoff` will wrap `analyzeSpectralRollOff` to return only the `cutoffHz` for backward compatibility with the tests.
- `isSuspiciousCutoff` will implement the ratio-based logic expected by the tests.

## Verification Plan

### Automated Tests
- Run `gradlew :app:compileDebugUnitTestKotlin` to verify the fix.
- Run the actual unit tests: `gradlew :app:testDebugUnitTest --tests "com.beatraxus.app.engine.AudioSpectrumAnalyzerTest"`

### Manual Verification
- None required as this is a test-only fix.
