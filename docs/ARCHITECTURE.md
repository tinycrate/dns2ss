# Architecture

## Purpose

dns2ss is a single-purpose Android VPN service. It sends DNS queries to a remote DNS resolver through a Shadowsocks server, and has a configurable choice of how much non-DNS traffic is also handled by the app.

The app does not maintain a user account, analytics, crash reporting, or remote configuration.

## Components

### `MainActivity` / `MainViewModel`

Landing screen only:

- service state and current mode;
- Fast / Strict / Full mode selection;
- Start / Stop;
- navigation to Settings and Diagnostics.

`MainViewModel` reads preferences and observes `VpnStatus`. If the mode is changed while the service is running, it sends a service restart request so the new mode's routes take effect.

### `SettingsActivity` / `SettingsViewModel`

Owns:

- Shadowsocks URI;
- remote DNS host and port;
- IPv6 toggle.

`SettingsViewModel` validates the SS URI and DNS target, saves to `AppPreferences`, and returns user-facing errors. IPv6 and mode changes apply after a VPN restart.

### `DiagnosticsActivity` / `DiagnosticsViewModel`

Displays live diagnostics from `DiagnosticsProvider` and a bounded in-memory log from `VpnLog`. The inline log is a scrolling, non-selectable view so drag gestures reach the scroller. **Select** / long-press opens a read-only selectable editor for copying individual text.

### `DnsVpnService`

Owns the Android `VpnService` lifecycle:

1. validate preferences;
2. allocate local tunnel/SOCKS ports;
3. start `sslocal`;
4. start the local SOCKS router;
5. establish the TUN interface;
6. start `hev-socks5-tunnel`;
7. publish `RUNNING`;
8. register the underlying-network callback.

Shutdown reverses the order, including the native TProxy state. Native HEV state is tracked separately from the Java flag so a failed/interrupted start can be reset and retried.

### `Socks5Router`

Local SOCKS5 server used by hev. Responsibilities:

- authenticate hev using per-session random credentials;
- route DNS (port 53) to the shared DNS tunnel;
- route other TCP/UDP directly in Strict or through sslocal's SOCKS server in Full;
- count direct/full/tunnel bytes;
- maintain UDP associations.

DNS uses one shared UDP socket into the sslocal tunnel, with transaction IDs multiplexed so many clients do not create one SS association each.

### `DnsTunnel` / `DnsCache` / `DnsWire`

- `DnsTunnel`: one UDP socket into the sslocal tunnel, pending transaction-ID table, response fan-out.
- `DnsCache`: bounded TTL-aware cache for positive and RFC 2308 negative answers.
- `DnsWire`: minimal DNS wire parser used by the cache to validate, normalize, and patch IDs/TTLs/question case.

Cache behavior:

- key includes normalized question + relevant query options, ignoring EDNS PADDING/NSID (Android varies padding length);
- positive entries expire at the minimum record TTL;
- negative entries use the SOA TTL;
- truncated and non-NOERROR/NXDOMAIN responses are not cached;
- TTLs are decremented on every hit;
- cache is cleared whenever the service/router restarts.

### `sslocal` process

The official shadowsocks-rust binary is packaged as `libsslocal.so` and launched as a child process. It supplies:

- a tunnel local for DNS (`127.0.0.1:<tunnelPort>` → remote DNS);
- a SOCKS local for Full mode.

The temporary JSON config is mode 0600 and deleted on stop. The process is monitored and its exit fails the VPN closed.

### HEV

`hev-socks5-tunnel` is the TUN-to-SOCKS engine. The app excludes itself from the VPN so its upstream connections do not loop. Debug builds additionally exclude `com.android.shell` for local emulator testing only.

`misc`/`socks5` settings include `pipeline: true`; this avoids the first-UDP-association stall that otherwise dominates cold DNS latency.

## Modes

### Fast

TUN routes:

- virtual IPv4 DNS `198.18.0.2/32`;
- virtual IPv6 DNS `fd00::2/128` when IPv6 is enabled;
- the current underlying network's resolver IPs (IPv4, plus IPv6 when enabled).

Non-DNS traffic stays on the native/kernel path and is not measured by the app.

### Strict

TUN routes:

- `0.0.0.0/0`;
- `::/0` when IPv6 is enabled.

Port 53 goes to the DNS tunnel. Other TCP/UDP is handled by the router as direct traffic. IPv6 disabled means IPv6 is not tunneled and may still use the underlying network.

### Full

Same TUN routes as Strict. All non-DNS TCP/UDP goes through the local sslocal SOCKS upstream; DNS still goes through the dedicated DNS tunnel to the remote resolver.

## IPv6

`Enable IPv6` controls whether the app adds:

- IPv6 TUN address;
- IPv6 virtual DNS;
- IPv6 default/route entries.

When disabled, IPv6 is not tunneled. This is a tunnel-scope switch, not a system-wide IPv6 firewall.

## Network changes

A `ConnectivityManager` network callback watches non-VPN networks. A debounced restart happens when the network signature (handle/interface/DNS) changes. This recalculates Fast resolver routes and rebuilds the SS connection. The restart path is shared with mode changes.

## Logging and diagnostics

`VpnLog` is a 250-line in-memory ring. sslocal's JSON log level only distinguishes Info/Debug/Trace, so real filtering is done with `RUST_LOG` (off/error/warn/info/debug). Inline logging defaults to Warnings.

`AppDiagnostics` is read on demand by the Diagnostics screen. Counters are atomics maintained in the router/DNS tunnel; there is no periodic stats poll.

## Data and files

Inside app-private storage:

- `sslocal.json` — temporary SS local config (0600, deleted on stop);
- `hev.yaml` — temporary hev config (deleted on stop);
- `shared_prefs/config.xml` — SS URI, DNS target, mode, verbosity, IPv6.

The SS password is in private app storage; it is not written to diagnostics. Trace-level sslocal output can contain config data and is not enabled by default.

## Threading

- Service actions run on `Dispatchers.IO`.
- `starting`/`stopping` flags serialize lifecycle transitions.
- `tproxyLock` serializes native HEV start/stop.
- Router uses a cached daemon thread pool.
- DNS tunnel uses one receive thread.
- Cache access is synchronized; pending DNS transactions use a concurrent map.
