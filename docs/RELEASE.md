# Release preparation

## Features frozen

No new features are planned for the first release. Changes should be bug fixes, tests, documentation, or build/release plumbing.

## Build variants

Debug APK (sideload/test):

```powershell
.\gradlew.bat :app:assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

Release build is currently unsigned:

```powershell
.\gradlew.bat :app:assembleRelease
```

Before publishing, add a private release signing config (keystore, alias, and passwords) outside the repository. Do not commit a keystore or passwords.

## Compatibility

- minSdk 29 (Android 10); required by the hev AAR.
- targetSdk 35.
- ABIs: `arm64-v8a`, `x86_64`.
- No 32-bit ABIs.

## Store/signing checklist

- [ ] create a private release keystore;
- [ ] configure release signing locally;
- [ ] set an immutable applicationId (`com.tiny.dns2ss` is already set);
- [ ] increment versionCode/versionName;
- [ ] run `:app:testDebugUnitTest` and a full phone regression;
- [ ] verify the APK with `apksigner verify --print-certs`;
- [ ] update `third_party/CHECKSUMS.txt`;
- [ ] archive a source tag and signed APK.

## Debug build note

The debug build excludes `com.android.shell` from the VPN for local test-server workflows. Release builds do not. Debug builds are debug-key-signed and are not suitable for store distribution.

## Checksums

`third_party/CHECKSUMS.txt` records SHA-256 values for the vendored HEV AAR, sslocal binaries, and the current debug APKs. Refresh it after the final build.
