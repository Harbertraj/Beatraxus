# Fix Google Drive Login in Release Builds

The goal is to resolve the issue where Google Drive login works in debug builds but fails in release builds. The investigation reveals that the app is using a legacy "anonymous" sign-in flow and has potentially weak ProGuard/R8 rules that might be stripping critical Google API components.

## User Review Required

> [!IMPORTANT]
> **Google Cloud Console Configuration**
> Even with these code changes, you **MUST** ensure that your **Release SHA-1 fingerprint** is registered in the Google Cloud Console (or Firebase Console) as an **Android OAuth 2.0 Client ID**.
> 1. In Android Studio, run the `signingReport` task to get your Release SHA-1.
> 2. Go to [Google Cloud Console Credentials](https://console.cloud.google.com/apis/credentials).
> 3. Ensure there is an OAuth 2.0 Client ID of type "Android" for package `com.beatraxus.app` with your **Release** SHA-1.
> 4. If you have `GOOGLE_CLIENT_ID` in your `local.properties`, ensure it is the **Web Client ID** associated with the same project.

## Proposed Changes

### [app]

Summary: Use the configured Google Client ID in the sign-in flow to ensure explicit project linkage, and strengthen R8 rules to prevent stripping of Google API components.

---

#### [MODIFY] [MainActivity.kt](file:///D:/Beatraxus/app/src/main/java/com/beatraxus/app/MainActivity.kt)

- Update `driveSignInOptions` to use `BuildConfig.GOOGLE_CLIENT_ID` if it is not empty.
- Although the current code tries to avoid `DEVELOPER_ERROR` by omitting `requestIdToken()`, providing the Client ID is the standard and more reliable way to ensure Play Services associates the app with the correct Cloud Project in release environments.
- Keep the `requestEmail()` and `requestScopes()` as they are.

#### [MODIFY] [proguard-rules.pro](file:///D:/Beatraxus/app/proguard-rules.pro)

- Change `-keepnames` to `-keep` for `GoogleAccountCredential` and `UserRecoverableAuthIOException`.
- Add additional keep rules for `com.google.api.client` internals that are often accessed via reflection.
- Add rules to preserve `GoogleSignInOptions` and its builder.

#### [MODIFY] [DriveAccountRepository.kt](file:///D:/Beatraxus/app/src/main/java/com/beatraxus/app/repository/DriveAccountRepository.kt)

- Minor optimization: Ensure that when creating `GoogleAccountCredential`, we are consistent with the scopes requested during sign-in.

---

## Verification Plan

### Automated Tests
- No automated tests available for OAuth flows as they require manual interaction and Play Services.

### Manual Verification
- **Build Release APK:** Generate a signed release APK using the release keystore.
- **Install and Test:** Install the release APK on a device.
- **Verify Sign-In:** Go to Settings -> Cloud Account -> Google Drive and attempt to add an account.
- **Check Logcat:** Monitor Logcat for tag `MainActivity` and `SigningCert`. Verify if `ApiException` status 10 still occurs. If it does, the user must update their Cloud Console.
