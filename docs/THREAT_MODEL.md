# Threat Model — Mr Nobody V1

## Scope

Mr Nobody is a browser. It reduces browser-controlled tracking. It does not
provide anonymity, hide IP addresses, or defeat all tracking.

## Assets

1. **Browsing history** — never stored by default; stored locally only if enabled.
2. **Cookies / site data** — cleared on demand; third-party blocked.
3. **User identity** — none exists in the app (no account, no ID).
4. **Device permissions** — camera/mic/location granted only on explicit site request.

## Threats addressed

| Threat | Mitigation |
|--------|------------|
| Browser records history silently | History OFF by default; `HistoryStore.add()` is a no-op when disabled |
| Browser uploads data | No backend, no analytics/telemetry SDKs |
| Ads/trackers follow the user | Local filter engine blocks known ad/tracker domains |
| Third-party cookies track across sites | `setAcceptThirdPartyCookies(view, false)` |
| Screenshot/recent-apps exposure (private tabs) | `FLAG_SECURE` on private tabs |
| Malicious site escalates via JS bridge | No `addJavascriptInterface`, no bridge at all |
| Cert validation bypass | We never implement `onReceivedSslError` (default = reject) |
| Accidental permission capture | Permissions requested only on site demand |

## Threats NOT addressed (by design, documented)

- IP-address-based tracking (needs Tor/VPN — out of scope).
- Advanced browser fingerprinting (V2 candidate; spoofing is deliberately not
  attempted in V1 to avoid breaking sites).
- Malware delivered via downloads (best-effort only; user judgment required).

## Failure model (filter engine)

- Corrupt/missing filter list → blocking disabled, browser still works.
- Filter engine error → never crashes the browser; falls back to allowing.

## What we must never do

- Disable certificate verification.
- Expose filesystem or native APIs to web content.
- Store credentials in plaintext.
- Add hidden tracking identifiers.
- Ship a developer proxy between the browser and websites.
