# Release preparation

## Features frozen

No new features are planned for the first release. Changes should be bug fixes, tests, documentation, or build/release plumbing.

## Build variants

Debug APK (sideload/test):

```powershell
.\gradlew.bat :app:assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

Release builds are R8-minified with resource shrinking. Use the release
pipeline for the signed artifact:

```powershell
.\scripts\build-release.cmd
```

It runs unit tests/lint, builds and zipaligns the release APK, then invokes
`apksigner`. In local mode it pauses and prompts for the **keystore
password** (and key password if different). The keystore must stay outside
the repository.

Defaults:

- keystore: `..\dns2ss-release.jks` relative to the repo;
- alias: `dns2ss`;
- output: `dist/dns2ss-<version>-release.apk`;
- mapping: `dist/mapping-<version>.txt`.

Use `-Keystore` and `-Alias` if your key differs; use `-NoSign` to only
build/test/zipalign.

### CI/CD mode

Set secrets in the CI environment and pass `-Ci`; no prompt is used:

```powershell
$env:DNS2SS_KEYSTORE_PATH = "D:\secrets\dns2ss-release.jks"
$env:DNS2SS_KEYSTORE_PASSWORD = "<from CI secret>"
$env:DNS2SS_KEY_PASSWORD = "<from CI secret>"
.\scripts\build-release.cmd -Ci
```

The script uses `DNS2SS_KEYSTORE_PASSWORD`/`DNS2SS_KEY_PASSWORD` via
`apksigner` environment arguments, so secrets do not appear in its command
line. Archive the R8 `mapping.txt` with every release.

## Compatibility

- minSdk 29 (Android 10); required by the hev AAR.
- targetSdk 35.
- ABIs: `arm64-v8a`, `x86_64`.
- No 32-bit ABIs.

## Store/signing checklist

- [ ] create a private release keystore;
- [ ] run `.\scripts\build-release.cmd`, or `-Ci` with CI secrets;
- [ ] set an immutable applicationId (`com.tiny.dns2ss` is already set);
- [ ] increment versionCode/versionName;
- [ ] run a full phone regression on the signed R8 APK;
- [ ] verify the APK with `apksigner verify --print-certs`;
- [ ] archive the signed APK, SHA-256, and R8 mapping.txt;
- [ ] archive a source tag.

## Debug build note

The debug build excludes `com.android.shell` from the VPN for local test-server workflows. Release builds do not. Debug builds are debug-key-signed and are not suitable for store distribution.

## Checksums

`third_party/CHECKSUMS.txt` records SHA-256 values for the vendored HEV AAR, sslocal binaries, and the current debug APKs. Refresh it after the final build.
