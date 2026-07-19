# Walkthrough - Google Drive Release Build Fix

I have applied changes to ensure Google Drive login works in release builds. Since the debug and release builds use the same signing certificate (SHA-1), the issue was likely caused by R8 minification stripping or renaming classes used by the Google SDKs via reflection.

## Changes Made

### Audio & Auth Infrastructure

#### [proguard-rules.pro](file:///D:/Beatraxus/app/proguard-rules.pro)
- Upgraded Google Auth and Drive keep rules from `-keepnames` to full `-keep`. This prevents R8 from stripping "unused" members that the Google SDK actually needs via reflection during the OAuth handshake.
- Added explicit keep rules for `ApiException`, `GoogleAccountCredential`, and `UserRecoverableAuthIOException` to ensure they survive obfuscation.

#### [MainActivity.kt](file:///D:/Beatraxus/app/src/main/java/com/beatraxus/app/MainActivity.kt)
- Updated `GoogleSignInOptions` to explicitly include the `GOOGLE_CLIENT_ID` (via `requestIdToken`) if configured. This helps Google Play Services explicitly link the app to your Cloud Project in release environments, rather than relying on implicit package matching.

## Verification Steps

1.  **Generate Release Build:** Re-build the application in "Release" mode.
2.  **Test Sign-In:** Launch the app and attempt to log in to Google Drive.
3.  **Check Logcat:** If it still fails, filter Logcat for `MainActivity`. The error message will now be more descriptive if it's a `DEVELOPER_ERROR` (status 10).

> [!TIP]
> If you still see "Status 10" in the logs, it means the Google Cloud Console definitely does not recognize the combination of your **Package Name** (`com.beatraxus.app`) and your **SHA-1**. Even if you updated it, sometimes it takes a few minutes to propagate.
