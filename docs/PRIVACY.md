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
- **Private tabs** share the engine's cookie/storage jars (WebView has no
  per-tab incognito profile). We set `FLAG_SECURE`, never write history for
  private tabs, and clear state where practical. Full storage isolation is a V2
  item.
- **WebView Safe Browsing** is disabled (it would send URLs to Google).

## Verification

`tools/privacy_audit.py` (run in CI) fails the build if any prohibited
permission, dependency, or JS-bridge pattern is introduced. Privacy regression
tests are specified in `tests/privacy/`.
