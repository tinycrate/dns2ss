# Contributing

## Workflow

- Keep commits focused: one feature, bug fix, or coherent test/doc change per commit.
- Run `:app:testDebugUnitTest` and `:app:assembleDebug` before committing.
- Sign off commits:

```text
Signed-off-by: Your Name <you@example.com>
```

- Do not commit `local.properties`, build outputs, APKs, keystores, passwords, or captured device logs.
- Do not add a remote DNS provider or analytics dependency.

## Code style

- Kotlin, 4-space indentation, existing package layout.
- Keep protocol/network logic out of Activities; use the view models and service/router layers.
- Preserve fail-closed DNS behavior.
- Avoid per-packet logging and periodic polling on the hot path.
- Add tests for wire-format, config, cache, and format helpers.

## Security

- Do not log credentials or DNS query names.
- Keep `sslocal.json` at mode 0600 and delete it on stop.
- Treat native HEV state as a managed resource; do not bypass the reset/stop path.
