# Architecture — Mr Nobody V1

## Overview

Mr Nobody is a native Android app: Java core (filters, WebView, agent, privacy)
and Flutter chrome. Android System WebView is used only as the rendering engine.

```
                       INTERNET
                          ▲
                          │
              ┌───────────┴───────────┐
              │  Android System WebView │   (rendering only)
              └───────────┬───────────┘
                          │  shouldInterceptRequest
              ┌───────────┴───────────┐
              │   Local Filter Engine │   (ours — ads/trackers → BLOCK)
              └───────────┬───────────┘
                          │
              ┌───────────┴───────────┐
              │  Native Browser App   │
              │        (Java)         │
              │  UI · Tabs · Navigation│
              │  Privacy · Storage    │
              │  Downloads · Permissions│
              └───────────────────────┘
```

No developer proxy exists between the browser and websites. The app connects
directly.

## Package map

```
com.mrnobody.browser
├── MrNobodyApp       Application: boots singletons, loads filter list
├── MainActivity      Browser chrome, tabs, navigation, privacy panel, menu
├── SettingsActivity  Settings screen (toggles, clear data, about)
├── core/
│   ├── Settings           SharedPreferences wrapper (single source of truth)
│   ├── PrivacyProfile     V2: Balanced/Strict/Maximum presets
│   ├── PerSiteSettings    V2: per-host overrides (blocking/js/cookies/location)
│   ├── BookmarksStore     V2: local bookmarks (SQLite)
│   ├── PrivacyReport      V2: daily aggregate counters (no URLs/titles)
│   └── PermissionStore    V2: per-site permission grants (dashboard)
├── blocking/
│   ├── FilterEngine  Loads blocklist, matches URLs, counts blocked requests
│   ├── Blocklist     In-memory compiled blocklist (domain + ABP-subset rules)
│   └── TrackingParams  V2: conservative URL tracking-parameter stripping
├── history/HistoryStore  SQLite; records visits ONLY when history is enabled
└── ui/
    ├── Tab           One tab = id, WebView, url, title, private flag
    └── TabManager    Add / switch / close / close-all / private tabs
```

## Key design points

- **History OFF by default.** `HistoryStore.add()` is a no-op unless the user
  enabled history. No history DB rows are written otherwise.
- **Private tabs** skip history and thumbnails, and clear on close. Cookie /
  storage isolation is real only when the device WebView supports
  `MULTI_PROFILE` — the privacy dashboard reports which. Private is not anonymous.
- **Blocking is local.** The filter list ships in `assets/` and is compiled at
  startup. No per-request server round-trip.
- **Cookies:** first-party allowed, third-party blocked via
  `CookieManager.setAcceptThirdPartyCookies(view, false)` (best-effort; documented
  limitation — WebView does not expose a fully hardened third-party cookie policy).
- **Permissions** are requested only when a site asks, via
  `WebChromeClient.onPermissionRequest`, with a Block/Allow dialog.
- **Downloads** are the app's own engine (pause / resume / die with the app);
  not Android `DownloadManager`. No cloud storage.
- **Minimal WebView bridge:** none in V1. No `addJavascriptInterface`.
- **No backup:** `allowBackup=false`, data-extraction rules exclude everything.

## Version / SDK

- `minSdk 26` (Android 8.0) — chosen to ship adaptive icons without legacy bitmap
  mipmaps, keeping the APK small.
- `targetSdk 35`, `compileSdk 35`.
- Java 11 source/target; Gradle runs on JDK 17.

## APK size

Release uses R8 + resource shrinking. Expected ~2–8 MB; hard gate 45 MB
(enforced in CI). See `docs/ENGINE_DECISION.md`.
