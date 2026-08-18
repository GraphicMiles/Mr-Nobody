# Privacy Specification — Mr Nobody

## Promise

> We don't track your browsing.

This is **not** a promise of anonymity. Websites can still see your IP address,
account activity, voluntarily submitted information, their own server logs, and
browser characteristics. Mr Nobody reduces *browser-controlled* tracking only.
It is not Tor, not a VPN.

## What the app contains (hard guarantees)

- No analytics SDK, no advertising SDK, no session recording.
- No user ID, no advertising ID usage, no browser fingerprinting.
- No browsing-history or search-history upload. No backend at all.
- No account, no login, no onboarding carousel.

## Defaults (privacy-first)

| Setting | Default |
|---------|---------|
| Save browsing history | **OFF** |
| JavaScript | ON (the web depends on it) |
| Search suggestions | OFF |
| Third-party cookies | Blocked |
| Ad / tracker blocking | ON (local) |
| Search engine | DuckDuckGo |

## Data flow

1. A page is requested.
2. Our local filter engine (`shouldInterceptRequest`) checks the URL against the
   bundled blocklist and blocks known ads/trackers, counting each block locally.
3. If history is OFF (default), nothing about the visit is written anywhere.
4. Cookies: first-party allowed, third-party blocked (best-effort — documented).

All counts (the privacy dashboard) are computed on-device. Nothing is uploaded.

## Known limitations (documented honestly)

- **Third-party cookie blocking** in WebView is best-effort
  (`CookieManager.setAcceptThirdPartyCookies(view, false)`); WebView does not
  expose a fully hardened third-party cookie policy. Blocking is reinforced by
  the tracker-domain filter.
- **Private tabs** isolate cookies and site storage only when this device's
  System WebView supports `MULTI_PROFILE`. The privacy dashboard reports that
  as a fact (`Isolated private tabs`), not an assumption. On an older WebView
  a private tab still writes no history, skips thumbnails, and is cleared on
  close — it is **not** a separate cookie jar. Private is not anonymous: same
  IP, same TLS stack.
- **WebView Safe Browsing** is disabled (`setSafeBrowsingEnabled(false)`).
  Enabling it would send visited URLs to Google. Malware checks stay with the
  OS / Play Protect.
- **Nobody mode** hides the IP only if Orbot (or a configured proxy) is up
  and the WebView can be proxied. Otherwise the mode is refused and the UI
  must show why. Fingerprint patches are forced on for a live Nobody session
  and restored when it ends. This is not Tor Browser and not anonymity.

## Verification

`tools/privacy_audit.py` (run in CI) fails the build if any prohibited
permission, dependency, or JS-bridge pattern is introduced. Privacy regression
tests are specified in `tests/privacy/`.
