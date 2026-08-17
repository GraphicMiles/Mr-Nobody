# Privacy Regression Tests — Specification

These tests verify the product's privacy guarantees. The JVM-runnable unit tests
(filter engine) live in `android/app/src/test/` and run in CI. The device-level
tests below require an instrumented runner (emulator/device) and are specified
here for implementation on the CI device matrix.

## 1. History OFF (default)

1. Fresh install, do **not** enable history.
2. Visit several sites; perform searches.
3. Kill the app; inspect local storage.
4. **Assert:** no history rows persisted (`HistoryStore.count() == 0`).

## 2. History ON → OFF

1. Enable history; visit sites; **assert** rows exist.
2. Disable history; visit more sites; **assert** count unchanged for new visits.

## 3. Network audit

1. Proxy the device traffic.
2. Browse normally.
3. **Assert:** no traffic to any browser-owned endpoint (there is none).

## 4. Ad blocking

1. Load an ad-heavy test page.
2. **Assert:** known ad requests are intercepted (empty response) and counted.

## 5. Tracker blocking

1. Load a tracker test page.
2. **Assert:** known tracker requests are blocked and counted.

## 6. No tracking SDKs (static)

`tools/privacy_audit.py` fails the build on any prohibited dependency/permission.
