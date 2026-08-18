# The next three phases — easiest to hardest

Written by reading the code, not the roadmap. Two roadmap items were stale and
are corrected below before anything is planned on top of them.

## Corrections found while checking

| Roadmap says | Code says |
|---|---|
| 1.4 Real private tabs — open | **Done and wired.** `ProfileManager.applyPrivate` is called from `MrNobodyWebView:108`, `destroyPrivate` from `TabWebViews:148`. |
| 1.6 Fingerprint defence — open | **Done and wired.** `FingerprintDefence.apply` is called from `MrNobodyWebView:114`. |

Both are feature-detected and degrade honestly. What is still missing for each
is *device verification* and *the UI telling the truth about them* — which is a
different job, and it lands in Phase 1 below.

The one thing that has never been true in any session: **nothing has been run
on a device. No APK has ever been built here.** That shapes the whole ordering.

---

# Phase A — Prove what already exists
**Effort: small. Risk: low. Value: high — it is the precondition for everything.**

Nothing new is built. This phase converts "the code looks right" into "we
watched it work", and closes the honesty gaps that opened when features landed
ahead of their UI.

### A1. Build an APK and run the suite on a device
The single highest-value item in this document. There are 88 pure-Java tests
passing against a hand-written JUnit shim because no jars and no network are
available here. Gradle `:app:testDebugUnitTest` has never run. Until an APK
exists, every claim about runtime behaviour is inference.

Concretely: `flutter build apk --release`, confirm the 70 MB gate passes with a
real number, install, and exercise download → pause → resume → complete.

**Done when:** an APK exists, its size is recorded, and the two download bugs
fixed in `3fd6020` are confirmed fixed *on hardware* rather than on a bench.

### A2. Surface what the device actually supports
`EngineInfo.describe()` exists and is reachable over the bridge at
`MainActivity:375`. No Dart screen reads it.

Multi-profile, document-start scripts and proxy override are all
device-dependent — they depend on the installed System WebView, not the OS
version. Right now a user on an old WebView gets silently weaker protection
than a user on a new one, and both see identical UI. That is the same failure
mode as the retracted private-tabs claim, one layer down.

**Done when:** the privacy screen shows WebView provider, version, and which
of the three capabilities are actually available on *this* device.

### A3. Make the settings toggles match reality
`Settings.isFingerprintProtection()` exists; `FingerprintDefence.apply` is
called unconditionally with a fixed seed. So the toggle reads a value nothing
consults. Either wire it or remove it — a toggle that enforces nothing is worse
than no toggle, which was roadmap item 1.1 and is still open.

### A4. Behavioural filter tests
Roadmap 1.5, untouched. The only filter testing is unit-level plus a static
grep. There is no assertion that a real request to a real tracker domain is
actually blocked. `BlocklistTest` tests the matcher; nothing tests the wiring
between the matcher and `shouldInterceptRequest`.

**Why here and not later:** it needs no new infrastructure, and it is the test
that would catch a privacy regression introduced by the 70 MB work.

---

# Phase B — Finish the agent, then route its traffic
**Effort: medium. Risk: medium. Value: the product's two headline claims.**

Two independent tracks that do not block each other. B1–B2 are agent
execution; B3–B4 are privacy routing. Per `V2_ARCHITECTURE.md` they must stay
in separate layers — if wiring Tor requires touching the planner, something has
leaked.

### B1. Make `Plan` drive execution
`Plan` is built and tested (12-step ceiling, `append` refuses past it, `abandon`
≠ finish) and **completely inert**. `DeterministicEngine.run` still executes a
fixed cascade: search → read up to 3 sources → synthesise, with a router that
picks one tool for simple actions.

This is the largest genuine capability gap in the agent. "Find the cheapest
version of X and download it" needs branching and replanning; the fixed cascade
cannot express it.

**Approach:** the cascade becomes the *default plan*, not the only path. That
keeps today's behaviour as a special case instead of a rewrite, and every step
already flows through `ToolPipeline`, so budgets, approval and spilling apply
unchanged.

### B2. Monitoring on top of schedules
`Schedule` and `Heartbeat` are wired; `PeriodicWorkRequest` fires. What does
not exist is anything *worth* repeating — price checks, availability, page
change. `PageAnchor` already measures "has this page meaningfully changed",
which is exactly the primitive a monitor needs.

Cheap, because the hard parts (persistence, coalescing, WorkManager's 15-minute
floor) are done.

### B3. Verify Tor/proxy on a device — or downgrade the claim
`OrbotTorRoute`, `ProxyRoute`, `NetworkGate` and `PrivacyController` all exist
and are unit-tested. **None has touched a real Orbot instance.** The standing
correction says Tor routing, proxy support, fingerprint defence and DNS
protection are all 🔴, and until A1 happens they stay 🔴 regardless of how
much code exists.

`ProxyController` is process-wide, so `NOBODY` is a whole-app mode, not a
per-tab badge. The UI must say that.

**Done when:** traffic is observed leaving through Orbot on 9050, and the
fail-closed path is observed actually failing closed when Orbot is absent.

### B4. DNS
Falls out of SOCKS5 for free — Chromium always resolves proxy-side for SOCKSv5,
with no client-side option. So this is mostly *verification and wording*, not
implementation. Worth stating explicitly because it is easy to over-engineer:
self-resolved DoH was already deferred for good reasons.

---

# Phase C — The remote layer
**Effort: large. Risk: high. Value: the paid product.**

Strictly ordered; each step is useless without the one before it. Nothing here
should start before Phase A, because it all assumes a device that runs.

### C1. `DeviceIdentity`
EC P-256 in Android Keystore — **not Ed25519**, for the reason recorded in
`V2_ARCHITECTURE.md`: Keystore cannot generate Curve 25519 through a public
API, so choosing it forces a software key, which destroys the only property the
design exists for. `PURPOSE_SIGN` plus `PURPOSE_AGREE_KEY`; ECDH needs API 31
and `minSdk` is 31.

Report `KeyInfo.getSecurityLevel()` honestly. Exclude the identity store from
Android auto-backup, or "never leaves the device" gains an asterisk.

### C2. Signed request envelope
Nonce, timestamp, signature, replay window. Pure Java, fully testable on a JVM
with no server in existence. This is the piece that can be built and verified
here.

### C3. Server-side verification
Different repository, different deployment. Out of scope for this codebase
beyond the client half.

### C4. `RemoteWorker` for real
Replaces the stub behind the existing `Worker` interface. Local stays the
default. The dispatcher registry already makes this additive.

**The claim discipline, restated because this is where products start lying:**
the remote worker executes tasks, so it sees URLs and page content in
plaintext. Encrypted transport and anonymous identity are real and claimable.
"The server cannot see what your task does" is false and must never be printed.

### C5. Credits
Only after C1–C4. A balance signed by a key that does not exist is not a
feature. Decide the lost-key policy *before* taking money — anonymity and
recovery are in direct tension, and the answer must be stated at the point of
purchase, not discovered afterwards.

---

## Where to start

**A1.** Build the APK. Everything else in this document is inference until
something has run on hardware once, and two of the three phases assume it.
