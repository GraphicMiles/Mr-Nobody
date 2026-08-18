# Mr Nobody

> **Nobody's watching.**

A small, **native** Android privacy browser. No ads, no trackers, no history by default.

Mr Nobody is a native Java application. All browser chrome, tabs, settings, privacy
controls, storage policy, ad/tracker filtering, and UX are implemented natively by us.
Rendering is delegated to the **smallest practical engine** — Android System WebView —
so the APK stays small (20–45 MB) instead of bundling a ~60 MB engine.

---

## Core promises

- **No account** — nothing to sign up for, nothing to log into.
- **No ads** — the browser never shows ads and never sells your attention.
- **No tracking** — no analytics SDK, no advertising SDK, no browser-owned telemetry.
- **History OFF by default** — nothing is stored unless you explicitly turn it on.
- **Local blocking** — ads and trackers are blocked on-device by our own filter
  engine (via `shouldInterceptRequest`); every block is counted locally.
- **Minimal permissions** — only what a browser genuinely needs.

> Privacy is not anonymity. Mr Nobody reduces *browser-controlled* tracking. It is
> not Tor, not a VPN, and it does not make you anonymous on the internet.

---

## Engine decision (short version)

The full rationale and measurement framework lives in
[docs/ENGINE_DECISION.md](docs/ENGINE_DECISION.md).

| Option | APK size | Engine | V1 verdict |
|---|---|---|---|
| **Android System WebView** | tiny (engine already on device) | system Chromium | ✅ **chosen** |
| GeckoView | +40–60 MB bundled | Firefox/Gecko | deferred — only if measured benefits justify size |
| Custom Chromium build | very large | Chromium | ❌ rejected for V1 |

The product constraint is a 20–45 MB APK. "Native app" does **not** mean "bundled
browser engine" — it means *our* UI, *our* filtering, *our* storage policy. WebView is
used strictly as the rendering engine; the privacy layer is entirely our own.

---

## Repository layout

```
Mr-Nobody/
├── app/                Flutter UI + native Android app
│   ├── lib/            Flutter screens (presentation only)
│   └── android/        Java core: agent, tools, WebView, filter engine, downloads
├── prototype/          Interactive HTML/CSS/JS UI prototype (design source of truth)
├── tools/              Python development-only tooling (filter compile/validate, size check)
├── filters/bundled/    Bundled blocklist (ads + trackers)
├── tests/              Unit + privacy regression tests
├── docs/               Architecture, engine decision, privacy, UI spec, threat model
└── .github/workflows/  GitHub Actions CI/CD (build + test + APK-size gate)
```

---

## Building

The authoritative build pipeline is GitHub Actions (see `.github/workflows`). Every
push builds a **release APK**, runs tests, and enforces the **45 MB size gate**.

To build locally:

```bash
cd android
./gradlew assembleDebug          # debug APK
./gradlew assembleRelease        # minified release APK (R8 + resource shrinking)
```

Requirements: JDK 17, Android SDK (compileSdk 35).

Expected release APK size: **~2–8 MB** (the engine ships with the OS, not the app).

---

## Version roadmap

- **V1** — tiny native browser: tabs, private tabs, history OFF by default, local
  ad/tracker blocking, privacy dashboard, downloads, permissions, no tracking.
- **V2** — advanced local privacy controls, per-site settings, privacy profiles,
  fingerprinting resistance, optional signed/community filter lists, and a
  benchmark-justified Rust privacy core (see docs/).

---

## License

MIT — see [LICENSE](LICENSE). Filter data licensing is documented in
`filters/bundled/`.
