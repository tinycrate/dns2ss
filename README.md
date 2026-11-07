# dns2ss

A single-purpose Android app that sends DNS to a remote resolver behind a Shadowsocks server, while optionally forwarding everything else directly. Built around Android's `VpnService`, [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel), and the official [shadowsocks-rust](https://github.com/shadowsocks/shadowsocks-rust) `sslocal` binary.

- App name: **dns2ss**
- Package: `com.tiny.dns2ss`
- Android 10+ (`minSdk 29`)
- `arm64-v8a` and `x86_64`
- Debug-signed APK for sideloading/testing; release signing is documented separately

## Modes

| Mode | DNS | Non-DNS traffic | Notes |
|---|---|---|---|
| **Fast** | System resolver → remote DNS over SS | Native/kernel direct | Lowest overhead; hardcoded plain DNS to other servers bypasses |
| **Strict** | Every TCP/UDP port 53 → remote DNS over SS | Direct through a userspace router | Catches hardcoded plain DNS; no DoH/DoT coverage |
| **Full** | Every TCP/UDP port 53 → remote DNS over SS | Through Shadowsocks via a local SOCKS upstream | All app traffic goes through SS; local LAN resources are not directly reachable |

All modes are fail-closed for DNS: if the SS tunnel or remote DNS is unavailable, DNS fails; there is no fallback to the system resolver.

## Architecture

```
VpnService TUN
  └─ hev-socks5-tunnel (socks5.pipeline enabled)
       └─ local SOCKS5 router (random port, random credentials)
            ├─ port 53 (TCP/UDP)
            │    ├─ shared DNS tunnel socket (one SS UDP association)
            │    ├─ TTL-aware DNS cache (positive + RFC 2308 negative)
            │    └─ sslocal tunnel local → SS server → remote DNS (default 127.0.0.1:53530)
            └─ other ports
                 ├─ Fast: not routed into the TUN
                 ├─ Strict: direct socket
                 └─ Full: sslocal SOCKS local → SS server
```

- The virtual DNS addresses are `198.18.0.2` and `fd00::2`.
- Fast mode routes the virtual DNS addresses plus the current underlying network's DNS server IPs into the TUN. The diagnostics screen lists the discovered resolver IPs.
- Strict and Full add `0.0.0.0/0` and, when IPv6 is enabled, `::/0`.
- The app itself is excluded from the VPN so its SS connection and direct sockets do not loop.
- **Debug builds only** additionally exclude `com.android.shell`; this exists for local emulator testing with an SS server running under `adb shell`. Release builds do not exclude shell.
- DNS requests share one long-lived UDP socket to sslocal. Transaction IDs are multiplexed, so clients do not each create an SS UDP association.
- Responses are cached by normalized question + query options, with EDNS padding/NSID ignored and EDNS COOKIE data ignored (cookies are per-transaction and vary between `dig`/resolver invocations). Cached responses drop the COOKIE option so a stale server cookie is never replayed. TTLs are decremented on every cache hit; negative answers use the SOA TTL; truncated/error responses are not cached.
- `socks5.pipeline: true` removes the first-UDP-association stall that otherwise dominated cold DNS latency. Cache hits are effectively immediate; cold queries are single-digit milliseconds on a local test path.
- Network changes trigger a debounced service restart, recalculating Fast-mode resolver routes and rebuilding the SS connection.
- There is no one-second stats polling. Diagnostics read live counters on demand.
- Traffic counters count TCP relay bytes as well as UDP payloads. Fast-mode non-DNS traffic bypasses the app and is therefore not measured.
- IPv6 is optional. When disabled, dns2ss does not add IPv6 TUN addresses, IPv6 DNS servers, or IPv6 routes; IPv6 is left to the underlying network and is not tunneled.

## Requirements

- Android 10+ (`minSdk 29`; required by the hev AAR).
- `arm64-v8a` or `x86_64`; 32-bit ABIs are not bundled.
- A Shadowsocks server using AEAD or AEAD-2022 and **UDP relay enabled**.
- The remote DNS target reachable from the SS server. Default: `127.0.0.1:53530`.
- The remote DNS service listening on both TCP and UDP on that host/port.
- Private DNS disabled; browser/app DoH disabled or blocked where possible.

## Build

Requirements: JDK 17, Android SDK platform 35, build-tools 35.0.0.

```powershell
$env:JAVA_HOME = 'D:\AndroidToolchain\jdk-17'
$env:ANDROID_SDK_ROOT = 'D:\AndroidToolchain\sdk'
.\gradlew.bat assembleDebug
```

Set `sdk.dir` in `local.properties` for your machine. APK:

```
app/build/outputs/apk/debug/app-debug.apk
```

Test client:

```powershell
.\gradlew.bat :testclient:assembleDebug
```

## Usage

1. Open dns2ss.
2. Open **Settings** and enter a Shadowsocks URI (`ss://...`) and the remote DNS target (`127.0.0.1:53530` by default). The **Enable IPv6** switch applies after a VPN restart; disable it if the server or network has no usable IPv6.
3. Select Fast, Strict, or Full on the landing page.
4. Press Start and grant VPN permission.
5. Use the Quick Settings tile after the first successful start. The tile shows the selected/active mode in its label and reconnects on mode changes.

The landing page is status + mode + power only. Configuration is on the Settings page; counters, cache stats, routed resolver IPs, log controls, and logs are on Diagnostics.

### Quick Settings tile

- First tap before configuration/permission opens dns2ss with a message and starts the consent flow.
- Tile state updates optimistically on tap.
- The tile shows the mode in its label (some launchers do not render `Tile.subtitle`); its subtitle/state description carries the run state.
- Mode changes update the tile through the service state.

## Diagnostics

The Diagnostics page shows:

- run state, mode, uptime;
- remote DNS target and SS server (no password);
- discovered Fast-mode routed DNS resolver IPs;
- DNS cache entries/hits/misses;
- DNS query/response/pending counts;
- tunnel/direct/full byte counters (formatted as B/KB/MB/GB) and errors;
- Private DNS mode;
- applied log verbosity (Off / Errors / Warnings / Info / Debug);
- IPv6 enabled/disabled;
- a vertically scrollable log with optional line wrapping and a Copy button.

The no-wrap log view scrolls vertically and horizontally; the **Wrap log lines** switch selects a wrapped, vertically scrolling view. Inline text selection is disabled so drag gestures always reach the log scroller. **Select** (or a long-press on the log) opens a read-only, selectable log dialog; **Copy** copies the whole visible log. Changing log verbosity shows the restart note; simply opening the page does not.

### Log notes

- sslocal's `no valid UDP server serving` WARN is a load-balancer probe warning at startup. With a single configured server it still chooses that server; created UDP associations confirm forwarding is working.
- TCP port 53 is proxied too, so sslocal may establish a TCP tunnel to the remote DNS address. This is normal TCP DNS (fallback/truncated responses or clients preferring TCP).

## Testing

From the repository root:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :testclient:assembleDebug
```

The JVM suite currently has 23 tests covering URI parsing, SS/HEV config builders, DNS cache positive/negative TTL, EDNS padding and COOKIE behavior, byte formatting, and SOCKS5 address/UDP parsing. Device and local-server tools are described in [docs/TESTING.md](docs/TESTING.md). Environment-specific test notes are kept under `tests/`.

## Documentation

- [Architecture](docs/ARCHITECTURE.md) — components, modes, DNS cache/tunnel, HEV lifecycle, network changes, threading.
- [Testing](docs/TESTING.md) — unit tests, test client, test DNS server, phone checklist.
- [Release](docs/RELEASE.md) — build variants, signing, compatibility, release checklist.
- [Contributing](CONTRIBUTING.md) — commit discipline, style, security expectations.

Repository layout: `app/` (Android app), `tools/testclient/` (separate test APK), `tools/testdns/` (JDK test DNS server), `tests/` (environment-specific notes), `scripts/` (vendored artifact helper), `third_party/` (licenses/checksums).

## Limitations

- **Private DNS / DoT** (port 853) bypasses port 53. Disable it; the app warns but does not block it.
- **DoH** (port 443) bypasses port 53. Disable browser secure DNS and block DoH hostnames in the remote DNS blocklist.
- **Fast mode** intentionally does not capture hardcoded plain DNS to arbitrary servers.
- **Loopback DNS** (`127.0.0.1:53`/`::1`) cannot be captured by a VPN.
- **IPv6 disabled** means IPv6 is not tunneled; apps may still use IPv6 through the underlying network. Disable IPv6 on the network itself for a hard block.
- Apps that explicitly bind sockets to the underlying network bypass the VPN.
- The SS server hostname is resolved by `sslocal` using the underlying network before/outside the tunnel; use an IP if that metadata leak matters.
- No per-app mode, always-on/lockdown, or 32-bit ABIs.
- The debug APK is debug-signed; a private release keystore is required for distribution.
- Physical-phone behavior remains the final acceptance gate; use [docs/TESTING.md](docs/TESTING.md) for the checklist.

## Third-party components

- `app/libs/hev-socks5-tunnel.aar` — hev-socks5-tunnel 2.19.3, MIT.
- `app/src/main/jniLibs/{arm64-v8a,x86_64}/libsslocal.so` — shadowsocks-rust v1.25.0, MIT.
- SockTun (MIT) was used as a reference for the VpnService/hev/QS-tile lifecycle.
- AndroidX, Material Components — Apache-2.0.

See `THIRD_PARTY_NOTICES.md` and `third_party/licenses/`.

## Test tools

- `tools/testclient` — separate APK that sends fake-resolver UDP/TCP DNS (including repeated same-socket and fresh-socket queries), NTP, and HTTPS checks; logs under tag `TestClient`.
- `tools/testdns/TestDnsServer.java` — tiny JDK DNS server returning `10.9.9.9` for test queries.
- `scripts/fetch-third-party.ps1` — optional re-download/verification helper for the vendored binaries.

## License

MIT. See `LICENSE`.
