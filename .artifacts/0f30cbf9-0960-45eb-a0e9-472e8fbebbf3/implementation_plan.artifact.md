# Implementation Plan - Fix Release Crash for SMBJ (MBASSY)

The user has identified a release crash occurring in the SMBJ library due to R8 obfuscation of the `net.engio.mbassy` library, which is used internally by `SMBClient`. This plan outlines the steps to add the necessary ProGuard rules, verify the fix by checking the R8 mapping file, and confirm the application works correctly in a release build.

## User Review Required

> [!IMPORTANT]
> This change involves modifying ProGuard/R8 rules. While it fixes a known crash, it increases the number of classes kept in the release build, which slightly increases the APK size.

## Proposed Changes

### Build Configuration

#### [MODIFY] [proguard-rules.pro](file:///D:/Beatraxus/app/proguard-rules.pro)

Add a new section for `MBASSY` directly above the `SMBJ` section to prevent R8 from obfuscating or stripping internal classes and constructors required for reflection-based event handling.

```proguard
# ══════════════════════════════════════════════════════════════════════════════
# MBASSY (event bus used internally by smbj's SMBClient)
# ══════════════════════════════════════════════════════════════════════════════
# mbassy scans @Handler methods via reflection and instantiates internal
# Subscription wrapper classes through a specific constructor signature.
# Without keeping the whole package (including constructors), R8 renames/strips
# that constructor and subscribe() throws MessageBusException at runtime.
-keep class net.engio.mbassy.** { *; }
-keepclassmembers class net.engio.mbassy.** { *; }
-keepclasseswithmembers class net.engio.mbassy.** {
    <init>(...);
}
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations
-dontwarn net.engio.mbassy.**
```

## Verification Plan

### Automated Tests
1.  **Clean Build**: Run `./gradlew clean` to ensure a fresh build.
2.  **Assemble Release**: Run `./gradlew assembleRelease` to generate the release APK and mapping file.
3.  **Verify Mapping**: Check `app/build/outputs/mapping/release/mapping.txt` to ensure `net.engio.mbassy` classes are NOT obfuscated.
    *   Command: `grep "net.engio.mbassy" app/build/outputs/mapping/release/mapping.txt | head -5`

### Manual Verification
1.  **Install APK**: Run `adb install -r app/build/outputs/apk/release/app-release.apk`.
2.  **Reproduction**: Open the app, navigate to `PlayerViewModel` usage (MainActivity), and verify no `MessageBusException` or `FATAL EXCEPTION` occurs in Logcat.
