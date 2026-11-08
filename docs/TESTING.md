# Testing

## Fast checks

From the repository root:

```powershell
$env:JAVA_HOME = 'D:\AndroidToolchain\jdk-17'
$env:ANDROID_SDK_ROOT = 'D:\AndroidToolchain\sdk'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :testclient:assembleDebug
```

Android lint is also part of the release gate:

```powershell
.\gradlew.bat :app:lintDebug
```

The pinned AndroidX/Material versions produce `GradleDependency` warnings only; dependency upgrades are intentionally out of scope for the freeze.

Unit coverage currently includes:

- SS URI parsing (SIP002, legacy, AEAD/2022, rejected plugins);
- sslocal and hev config builders;
- DNS cache positive TTL/ID/question-case behavior, expiry, EDNS padding/COOKIE key normalization, cached-response COOKIE stripping, and SOA-based negative caching;
- byte-unit formatting;
- SOCKS5 address/IPv4/IPv6/domain/UDP/fragment handling.

## Physical-device benchmark

`BenchActivity` (in the test-client APK) measures, with a fresh socket per DNS query:

- 12 common domains, 1 cold + 3 warm queries each, shuffled per run;
- cold/warm raw DNS to the **active network's resolver** (the virtual `198.18.0.2` while dns2ss is running);
- TCP connect to `1.1.1.1:443`;
- UDP NTP to a public server;
- Android system DNS;
- configurable HTTPS downloads from `speed.cloudflare.com`.

Run it under each mode or under another VPN. The HTTP size defaults to
5 MB; the example uses three 50 MB transfers for a stable throughput sample:

```powershell
adb shell am force-stop com.tiny.dns2ss.testclient
adb shell am start -n com.tiny.dns2ss.testclient/.BenchActivity --ei bytes 50000000 --ei httpRuns 3
adb logcat -s Bench:I
```

Repeat the whole benchmark two or three times and use the median. For CPU,
sum `/proc/<pid>/stat` utime+stime across every process in the VPN package
UID (including proxy children), subtract a 5 s idle baseline, and divide by
the benchmark wall time.

The DNS target is intentionally not hardcoded to `1.1.1.1`. In Fast mode, raw DNS to an arbitrary public resolver bypasses the TUN by design; only system-DNS/underlying-resolver traffic is routed. Using `LinkProperties.dnsServers` tests the actual Fast path.

## Device test client

Install:

```powershell
adb install -r tools/testclient/build/outputs/apk/debug/testclient-debug.apk
adb shell am start -n com.tiny.dns2ss.testclient/.TestActivity
adb logcat -s TestClient:I
```

The client checks:

1. repeated fake-resolver DNS queries on one socket (cache-hit timing);
2. fresh-socket fake-resolver queries (cold path);
3. fake-resolver TCP DNS;
4. non-DNS UDP NTP;
5. HTTPS `example.com`.

Expected by mode:

- **Fast**: fake-resolver TCP/UDP fails by design; HTTPS works.
- **Strict**: fake-resolver TCP/UDP succeeds; HTTPS/NTP work.
- **Full**: fake-resolver TCP/UDP, NTP, and HTTPS work; SS logs show non-DNS traffic.

## Local test DNS server

`tools/testdns/TestDnsServer.java` is a small JDK UDP+TCP DNS server that answers with `10.9.9.9`:

```powershell
java tools/testdns/TestDnsServer.java 53530
```

Run the SS server separately and point the app's remote DNS target at the test DNS server. The `tools/testdns` server is a test aid, not part of the APK.

## WSA test record

Environment-specific notes and limitations from the emulator run are kept in [`tests/wsa-2026-11-07.md`](../tests/wsa-2026-11-07.md). Those notes are intentionally excluded from the main README.

## Physical-phone checklist

- Fast mode resolves through the remote DNS; hardcoded plain DNS is not claimed to be captured.
- Strict mode captures fake resolver TCP/UDP DNS and source-forges the replies.
- Full mode proxies non-DNS TCP/UDP through SS; local LAN resources are not directly reachable.
- Stop removes the VPN and terminates `sslocal`.
- Mode switches and a Wi-Fi/cellular transition complete without a HEV start error.
- IPv6 disabled: no IPv6 TUN address/DNS/route; no SS IPv6 errors from the app.
- Quick Settings tile shows mode and toggles; first tap before setup opens the app with feedback.
- Diagnostics log scrolls vertically/horizontally; wrap switch works; Select opens a full-size selectable dialog.
- Applied log verbosity changes after Stop/Start and is shown in the summary.
- Punch-hole/status-bar insets are clear on all three screens.
- Speed test numbers appear in direct/full metrics in the appropriate mode.
