# Testing

## Automated

From the repository root:

```powershell
$env:JAVA_HOME = '<path-to-jdk-17>'
$env:ANDROID_SDK_ROOT = '<path-to-android-sdk>'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

This runs the JVM unit suite, builds the app, and runs Android lint. The test client is a separate APK:

```powershell
.\gradlew.bat :testclient:assembleDebug
```

## Release build

Build the R8/resource-shrunk release without signing:

```powershell
.\scripts\build-release.cmd -NoSign
```

For a signed release, see [RELEASE.md](RELEASE.md). The release gate is:

```powershell
.\scripts\build-release.cmd
```

## Device test client

Install the test client and start its main test screen:

```powershell
adb install -r tools/testclient/build/outputs/apk/debug/testclient-debug.apk
adb shell am start -n com.tiny.dns2ss.testclient/.TestActivity
adb logcat -s TestClient:I
```

`TestActivity` sends UDP/TCP DNS through the fake resolver, plus NTP and HTTPS checks. `BenchActivity` measures DNS latency and HTTPS throughput with a fresh socket per query:

```powershell
adb shell am start -n com.tiny.dns2ss.testclient/.BenchActivity --ei bytes 50000000 --ei httpRuns 3
adb logcat -s Bench:I
```

`BatteryLoadActivity` provides a reproducible light workload with a partial wake lock, periodic DNS queries, and small downloads:

```powershell
adb shell am start -n com.tiny.dns2ss.testclient/.BatteryLoadActivity
adb logcat -s BatteryLoad:I
```

## Test DNS server

`tools/testdns/TestDnsServer.java` is a JDK UDP+TCP DNS server that answers test queries with `10.9.9.9`:

```powershell
java tools/testdns/TestDnsServer.java 53530
```

Run an SS server separately and point dns2ss at this DNS server. The test server is not part of the APK.

## Static checks

```powershell
.\gradlew.bat :app:lintDebug :app:lintRelease
```

The pinned AndroidX/Material versions produce dependency-version warnings; upgrades are intentionally out of scope for this release.
