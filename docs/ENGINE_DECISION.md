# Engine Decision Record — Mr Nobody V1

**Status:** ADOPTED (System WebView) · **Date:** 2026-08-17 · **Decider:** product owner + engineering

## 1. Requirement (amended V1 spec)

> Use the smallest practical modern browser engine. The application itself must be
> native Android. A system-provided rendering engine such as Android System WebView
> is acceptable and **preferred** if it allows the APK to remain within the 20–45 MB
> target. A bundled engine such as GeckoView should only be selected if its benefits
> materially outweigh the APK-size constraint.

"Native" refers to the **application** (our chrome, settings, tabs, storage policy,
filtering, UX). It does **not** mandate bundling a rendering engine.

## 2. Options

| Option | APK size | Engine | Control | Maintenance | V1 verdict |
|---|---|---|---|---|---|
| **Android System WebView** | ~2–8 MB (engine already on device) | system Chromium, OS-updated | Medium–High (rendering via system; filtering ours) | Low (we ship no engine) | ✅ **chosen** |
| **GeckoView** | +40–60 MB bundled | Firefox/Gecko | High | High (track + rebuild on each Gecko release) | Deferred |
| **Custom Chromium build** | Very large (100s of MB, multi-week builds) | Chromium | Very high | Very high | ❌ rejected |

## 3. Decision

**Android System WebView as the rendering engine, everything else native.**

- Mozilla itself notes WebView's major advantage is a smaller application package
  because the engine is already installed on the device.
- System WebView is updated through Google Play independently of our app, so we
  inherit security patches without shipping/re-signing an engine.
- All privacy-critical behavior — history policy, ad/tracker blocking, cookie
  policy, permissions, storage clearing — is implemented by us in Java and remains
  fully under our control. The filter engine hooks `shouldInterceptRequest`, so the
  engine (WebView) never sees our policy decisions as something it can override.

## 4. What would justify revisiting (GeckoView)

GeckoView becomes the justified choice only if **measured** evidence shows WebView
blocks a required capability. Candidate triggers:

1. A required content-blocking capability WebView cannot express (WebView exposes
   `shouldInterceptRequest`, which is sufficient for domain/pattern blocking in V1).
2. A measured page-compatibility gap that harms the core promise.
3. A product decision that the browser must work identically on devices with an
   outdated/absent WebView provider.

## 5. Measurement framework (runs in CI)

Every CI build reports, for the **release** build (R8 + resource shrinking):

- Universal APK size and, if reintroduced, per-ABI split sizes.
- The `.dex` / resource / asset breakdown (via `tools/apk_size_check.py`).

A future GeckoView comparison would additionally measure on low-end hardware:
startup time, RAM (RSS) under load, page-load time on a fixed page set, and the
blocked-request hit-rate on ad-heavy pages. These require an instrumented device/CI
runner (see `tests/`).

## 6. Hard gate

- Release APK **must be ≤ 45 MB**. CI fails the build if it exceeds this
  (`tools/apk_size_check.py`, enforced in `.github/workflows/build.yml`).
- The 20–45 MB target is a product identity, not a soft preference.
