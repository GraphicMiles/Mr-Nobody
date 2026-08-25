# Mr Nobody code audit

Date: 2026-08-25

## Scope

Audited the Flutter shell, browser/tab lifecycle, Android platform-view bridge, WebView lifecycle, download UI/engine boundary, and the release/debug changes on `main`.

The working tree was cloned from `GraphicMiles/Mr-nobody`; Git author is configured as `rfarouq69@gmail.com`.

## Root causes found

### 1. Release-only black page / unreliable page recovery — fixed

The release regression was introduced in `dc40c44` and compounded by the later release changes:

- release enabled R8 minification and resource shrinking while debug stayed unminified;
- the browser was changed to a persistent platform-view overlay;
- `913bd56` disabled R8 and reverted the persistent overlay, which removed the most obvious failure but also brought back repeated platform-view teardown/recreation;
- release then excluded `x86_64` native libraries while debug still contained them.

The last item is a concrete debug/release mismatch: an x86_64 emulator/device can run the debug APK but cannot create the Flutter/WebView native surface from the public APK. The release build now keeps the same ABI payload as debug. R8 and resource shrinking remain disabled.

A second recovery defect was present in `onRenderProcessGone`: the dead WebView was released, but Dart only tried `reload()` against the old platform-view key. That cannot recover a destroyed renderer, so the view remained black until a larger navigation/reload happened. The Android engine now increments a platform-view generation on renderer failure, forcing Flutter to mount a fresh WebView using the last known URL.

### 2. Bottom browser controls disappearing / being clipped — fixed

`BrowserNav` was painted in a Flutter `Stack` above an Android platform view. Depending on platform-view composition and timing, Android could win the z-order. Symptoms match the report: controls disappeared, only a clipped/small element remained, and the page could appear black underneath.

The browser controls now occupy a sibling region outside the platform-view bounds. Their height is animated to zero when chrome is hidden, but when visible it is deterministic and remains tappable in both debug and release builds. This also prevents the page from being covered by a bar at an inconsistent height.

### 3. Direct navigation bypassed tab state — fixed

`AppShell._openBrowser` called `tab.engine.loadUrl()` directly. That skipped `BrowserTab.load`, leaving Dart loading/error state, timeout handling, and the recreation URL out of sync with native navigation. It now calls `tab.load()`.

### 4. Download speed presentation — fixed

The download screen correctly samples real byte deltas per active download, but it also displayed Android's `NetworkCapabilities.getLinkDownstreamBandwidthKbps()` as “Link capability”. That value is an estimate and is commonly stale or OEM-provided (the reported 105 Mbps symptom). It was easy to interpret as the current transfer speed.

The misleading link-capability row was removed. The top card now exposes only the measured aggregate transfer speed from active downloads, while the connection row remains available for transport/metered state.

## Remaining risks / device verification

- Flutter and Gradle are not installed in this workspace, so `flutter analyze`, Flutter tests, and Android JVM tests could not be executed here.
- Physical-device verification is still required for Android System WebView renderer crashes, WebView versions, OEM memory pressure, Tor startup, Storage Access Framework providers, and x86_64 emulator behavior.
- The existing retained-WebView cache is capped and evicts under memory pressure. An evicted tab should reload from its last URL; this path should be exercised on a low-memory device.
- The public release should be built and installed on at least one arm64 phone and one x86_64 emulator before publishing.

## Validation performed

- Reviewed history and diffs for `606c2b0`, `dc40c44`, `913bd56`, `39f2a74`, `ecd621a`, `05986b5`, and `4515c26`.
- Traced Flutter tab state through `BrowserTab`, `NativeWebViewEngine`, `MrNobodyWebView`, and `TabWebViews`.
- Confirmed no remaining hardcoded `105` value in download/network code; the only remaining `105` match is an unrelated logo animation coordinate.
