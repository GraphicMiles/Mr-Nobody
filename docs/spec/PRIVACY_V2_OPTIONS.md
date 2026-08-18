# Privacy V2 — what is actually buildable, and what it costs

Scope: the four items marked 🔴 in the V2 status (Tor, proxy, fingerprint
defence, DNS), plus the private-tab claim that was overstated. Budget: keep the
APK under 70 MB, which is now the CI hard gate
(`tools/apk_size_check.py`; product target remains 20–45 MB).

Everything below is constrained by one fact: **we host the system WebView, we
do not ship an engine.** That is what keeps the APK small, and it is also what
takes several options off the table. Nothing here proposes bundling Chromium.

---

## 0. The constraint that shapes every other decision

`ProxyController.setProxyOverride()` sets the proxy **for every WebView in the
process**. The API is explicit: "Sets ProxyConfig which will be used by all
WebViews in the app." There is no per-WebView proxy, and there never has been —
the Chromium team's own answer was that the proxy is global to a profile and
WebView uses one profile.

**Consequence:** "one tab on Tor while another is direct" is not buildable in a
single process. A Nobody Mode that routes through Tor is a **mode the whole app
enters**, not a per-tab badge. Any UI that implies otherwise is lying.

This is worth designing around rather than fighting. Modes are also easier to
explain than per-tab states, and easier to make honest.

---

## 1. Private tabs — the overstated claim, and the real fix

### What is true today

`Tab.java` said it plainly: WebView has no per-tab incognito profile, so
private tabs share the global cookie/storage jar. What is actually enforced is
no history written, `setDatabaseEnabled(false)`, `LOAD_NO_CACHE`, and
cache/formdata cleared on close. Calling that "isolated storage" was wrong.

### The real fix: MULTI_PROFILE

`androidx.webkit` 1.9.0 added a multi-profile API. Each `Profile` owns its
**own `CookieManager`, `WebStorage`, `ServiceWorkerController` and
`GeolocationPermissions`**, and the documentation states information is not
shared between profiles. A WebView is bound to one with
`WebViewCompat.setProfile(webView, name)`, and `ProfileStore.deleteProfile()`
destroys the data.

This is genuine storage isolation — the thing we said was impossible. It was
impossible when `Tab.java`'s comment was written; it is not now.

**Rules that matter:**

- `setProfile` must be called **before the WebView navigates anywhere**, or it
  throws `IllegalStateException`. It slots into `MrNobodyWebView` construction,
  not later.
- Guard every call with
  `WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)`. This is a
  WebView-version feature, not an API-level one: a device on an old System
  WebView will not have it.
- Multi-process WebView is reported as a prerequisite in AndroidX's own
  feature-gating source. Verify on a real device before promising it.
- The legacy `CookieManager.getInstance()` keeps operating on the Default
  profile. Our existing cookie policy code has to move to the profile's manager
  or it will silently configure the wrong jar.

**Honest limit:** this isolates storage. It does not isolate the network
identity — same IP, same TLS stack, same fingerprint. Private ≠ anonymous, and
the UI should not blur the two.

**Cost:** `androidx.webkit` is a compat wrapper over the system WebView, not an
engine. It carries no browser binary. Measure it with the existing size gate
rather than trusting an estimate, but this is not where the megabytes go.

---

## 2. Proxy support — cheapest real win

`ProxyConfig.Builder.addProxyRule()` accepts `[scheme://]host[:port]` where the
scheme may be **HTTP, HTTPS or SOCKS**, defaulting to port 1080 for SOCKS. So
proxy support is roughly a settings screen plus a builder call. `addDirect()`
gives a fallback, `addBypassRule()` an exception list.

**Two limits to state in the UI, not bury:**

- **No SOCKS5 authentication.** Chromium has never implemented it and closed
  the request as won't-fix. Username/password SOCKS proxies will not work.
  HTTP(S) proxies can authenticate via `onReceivedHttpAuthRequest`.
- **Process-wide** (see §0).

**Cost: effectively zero.** No new native code.

---

## 3. Tor — buy it, do not build it

### The cheap path: Orbot over SOCKS

Because `ProxyConfig` already speaks SOCKS, Tor is *the proxy feature pointed
at `127.0.0.1:9050`*. Orbot is a separate app the user installs; we ship no Tor
binary and add **no APK weight at all**.

It also solves DNS for free — see §4.

Guardian Project's NetCipher is the reference implementation for
WebView + Orbot and is worth reading before writing our own.

**Costs:** requires a second app; if Orbot is not running, requests must
**fail closed**, never silently fall back to direct. `addDirect()` is exactly
what we must *not* do in this mode.

### The expensive path: Arti (embedded Rust Tor)

Arti is the Tor Project's Rust rewrite, designed to be embedded as a library
rather than driven over SOCKS — genuinely elegant, and it would remove the
Orbot dependency.

**Reject it for this budget.** The Tor Project itself calls binary size "one
still-uncracked challenge" for Arti, and the client crate pulls a very large
dependency tree. Adding a Rust static library per ABI to a 45 MB app aiming at
70 MB is the single fastest way to lose the size argument, and it would also
force NDK toolchains into a build that currently has none.

Revisit only if the size story changes, and only behind ABI splits.

---

## 4. DNS protection — mostly a side effect, not a feature

Android's **Private DNS is DoT, system-wide, and user-configured. An app cannot
set it.** Android 11+ supports DoH, but the settings field takes a DoT hostname,
and there is no public API for us to override it per-app.

So there are two honest options:

**(a) Get it free from SOCKS5.** In Chromium, when the proxy scheme is SOCKSv5,
**name resolution is always done proxy-side**. Route through Tor or a SOCKS5
proxy and DNS stops leaking to the local resolver as a direct consequence. No
code beyond §2.

**(b) Resolve it ourselves.** Intercept in `shouldInterceptRequest` and fetch
via OkHttp with a DoH resolver. Technically possible — and we already own that
callback for filtering.

**Recommend (a); treat (b) as a trap.** Option (b) means we stop handing the
request to WebView and start reimplementing the browser's network layer:
HTTP/2, connection reuse, caching, range requests, compression, cookie
handling. That is a large regression in speed and correctness, in exchange for
a property option (a) gives away.

State the limitation plainly: with no proxy, DNS goes wherever the OS sends it,
and the fix is the system Private DNS setting.

---

## 5. Fingerprint defence — real, cheap, and must not be oversold

There is already an `isFingerprintProtection()` toggle in `Settings` that
**enforces nothing**. A switch that does nothing is worse than no switch; it
must either be implemented or hidden.

The hook is `WebViewCompat.addDocumentStartJavaScript()` (`androidx.webkit`
1.9.0, feature `DOCUMENT_START_SCRIPT`): it runs **before any page script**,
applies to **iframes**, and takes an origin allowlist. That is enough to patch
the usual surfaces before a page can read them — canvas readback noise, audio
buffer noise, WebGL vendor strings, `screen`/`devicePixelRatio` rounding,
`hardwareConcurrency`, timezone.

Two supporting APIs: `WebSettingsCompat.setUserAgentMetadata()` for UA client
hints (freezing the UA string alone is pointless while client hints still leak
the model), and, in newer webkit, script injection into an **isolated world**
so page code cannot inspect or undo our patches.

**Be honest about the ceiling.** Bromite — which does far more of this than we
will — states its anti-fingerprinting mitigations "are not to be considered
useful for journalists and people living in countries with freedom
limitations". Ours will be weaker. Noise raises cost; it does not confer
anonymity. Never label this as such.

**Cost: kilobytes of JavaScript.**

---

## 6. The radical option: don't ship the engine, replace it

Cromite (the maintained fork of the now-dormant Bromite) publishes a
**SystemWebView** APK with ad blocking, anti-fingerprinting flags, DoH with any
IETF endpoint, and network-isolation features already compiled in.

The radical part: **a user who installs Cromite SystemWebView upgrades every
one of these properties for us, at zero bytes of our APK**, because we host the
system WebView rather than bundling one. Our entire architecture is
accidentally a plugin socket for a hardened engine.

What we can honestly do:

- **Detect** the WebView provider and version via `WebViewCompat` and report it
  in the privacy dashboard. "Engine: Cromite 1xx — hardened" vs "Android System
  WebView 1xx — standard" is real, checkable information.
- **Feature-detect and light up** what the installed engine supports, instead
  of assuming a floor.
- **Document** the swap as an advanced step.

What we must not do: bundle it, auto-install it, or require root/Magisk. Ship a
browser that is better on a hardened engine and honest on a stock one.

---

## 7. Recommendation

| # | Item | Mechanism | APK cost | Verdict |
|---|------|-----------|----------|---------|
| 1 | Private tabs (real) | `MULTI_PROFILE` profiles | wrapper only | **Do first** — closes a claim we already made |
| 2 | Proxy | `ProxyController` + `ProxyConfig` | ~0 | **Do** — cheapest real capability |
| 3 | Tor | Orbot SOCKS on 9050, fail-closed | 0 | **Do** — Tor without shipping Tor |
| 4 | DNS | proxy-side resolution via SOCKS5 | 0 | **Free with #3** |
| 5 | Fingerprint | `addDocumentStartJavaScript` | KBs | **Do** — or delete the dead toggle |
| 6 | Engine report | `WebViewCompat` version/provider | 0 | **Do** — honesty feature |
| — | Arti embedded Tor | Rust static lib per ABI | large | **Reject at this budget** |
| — | Self-resolved DoH | OkHttp in `shouldInterceptRequest` | ~1 MB + regressions | **Reject** — reimplements the network stack |

Total added weight for items 1–6 is one AndroidX wrapper plus Java and
JavaScript. The 70 MB ceiling is not the binding constraint — **the binding
constraints are that the proxy is process-wide and that fingerprint defence has
a low ceiling.** Both are design problems, not budget problems.

### Suggested modes, given §0

```
NORMAL   tracker blocking, default profile
PRIVATE  own Profile (isolated cookies/storage), destroyed on exit
NOBODY   PRIVATE + all traffic via Orbot SOCKS, fail-closed,
         DNS resolved proxy-side, fingerprint patches on
```

`NOBODY` is a whole-app mode because the proxy is process-wide. Saying so in
the UI is more useful than a per-tab badge that cannot mean what it appears to.

---

## 8. What has not been verified here

Written from API documentation and release notes, not from a device:

- No APK was built, so every size statement is an argument from what the
  dependency contains, not a measurement. Gate them with
  `tools/apk_size_check.py`.
- `MULTI_PROFILE`'s multi-process prerequisite comes from AndroidX's
  feature-gating source and needs confirming on hardware.
- Which devices actually report `MULTI_PROFILE`, `DOCUMENT_START_SCRIPT` and
  `PROXY_OVERRIDE` as supported is a field question. Every call site must be
  feature-detected regardless.
