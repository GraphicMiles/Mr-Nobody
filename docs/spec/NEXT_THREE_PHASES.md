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

### A1. Build an APK and run the suite on a device — ⚠️ PARTIALLY DONE

**Done:** the whole module now compiles against the real Android SDK, and the
whole test suite runs. `tools/jvm_test.sh` fetches `android.jar`, the androidx
AARs, the Flutter embedding jar and real JUnit, generates the `R` class that
AAPT would, and drives `javac` directly — **169 main classes, 0 errors, 382/382
tests passing** in about 9 seconds from a cold cache.

That closes a real gap. Before this, no session had ever compiled the Android
half of the app: verification was a hand-written JUnit shim over the pure-Java
classes, reaching 88 tests and compiling nothing that touched an Android type.
Every claim about `TaskStore`, `DownloadService`, `MrNobodyWebView` or
`ProfileManager` was inference from reading. Now they are compiled facts.

Two things it caught that reading had not:

- `android.jar` ships **stubs that throw** `RuntimeException: Stub!`. Two
  `SearchProvidersTest` cases fail against it and pass against a real
  `org.json`, so the script puts a real implementation ahead of `android.jar`
  on the classpath. Without that, tests fail for reasons unrelated to the code.
- Some tests read source files relative to the Gradle module directory and fail
  spuriously from anywhere else. The script `cd`s there, as Gradle does.

**Still not done, and not doable here:** no APK. That needs the Flutter SDK and
a Gradle run; this machine has neither, has 1 GB of RAM, and `sdkmanager`
requires a newer JDK than the Java 11 installed. So the 70 MB gate has still
never been measured against a real artifact, and **nothing has run on a
device.** Every runtime claim — the download notification fix, Orbot routing,
multi-profile isolation — remains unverified in the only way that counts.

Original scope, still outstanding: `flutter build apk --release`, record the
size, install, exercise download → pause → resume → complete.
The single highest-value item in this document. There are 88 pure-Java tests
passing against a hand-written JUnit shim because no jars and no network are
available here. Gradle `:app:testDebugUnitTest` has never run. Until an APK
exists, every claim about runtime behaviour is inference.

Concretely: `flutter build apk --release`, confirm the 70 MB gate passes with a
real number, install, and exercise download → pause → resume → complete.

**Done when:** an APK exists, its size is recorded, and the two download bugs
fixed in `3fd6020` are confirmed fixed *on hardware* rather than on a bench.

### A2. Surface what the device actually supports — ✅ DONE
`EngineInfo.describe()` exists and is reachable over the bridge at
`MainActivity:375`. No Dart screen reads it.

Multi-profile, document-start scripts and proxy override are all
device-dependent — they depend on the installed System WebView, not the OS
version. Right now a user on an old WebView gets silently weaker protection
than a user on a new one, and both see identical UI. That is the same failure
mode as the retracted private-tabs claim, one layer down.

**Done:** `NativeBridge.engineInfo()` now calls the `engineInfo` channel that
already existed on the native side and was never invoked from Dart. The privacy
screen gained a **Web engine** section showing the engine name and version plus
multi-profile, document-start-script and proxy-override support, each read from
`WebViewFeature` rather than inferred. Where something is unavailable, an
explanatory note says it depends on the installed Android System WebView and
not on Mr Nobody.

Unknown renders as `—`, never as "unavailable": failing to ask and being told
no are different, and only one is a fact.

### A3. Make the settings toggles match reality — ✅ DONE (and the diagnosis was wrong)
**The earlier diagnosis in this document was wrong.** `FingerprintDefence.apply`
is *not* called unconditionally — `MrNobodyWebView:113` guards it with
`settings().isFingerprintProtection()`, and `PrivacyProfile` sets that true for
STRICT and MAXIMUM. The plumbing is complete.

The real problem was subtler and worse: the setting **defaults to false**, and
the only thing that changes it is the privacy profile. So on a default install
fingerprint defence is *off*, and nothing anywhere said so. A user reading
"Fingerprint defence: Available" would reasonably conclude it was running.

Fixed by reporting both facts: the privacy screen now distinguishes
`Not on this device` (the WebView cannot), `Off — raise privacy profile` (it
can, and is not switched on) and `On`. Supported and enabled are different
claims and now look different.

### A4. Behavioural filter tests — ✅ DONE
Roadmap 1.5, untouched. The only filter testing is unit-level plus a static
grep. There is no assertion that a real request to a real tracker domain is
actually blocked. `BlocklistTest` tests the matcher; nothing tests the wiring
between the matcher and `shouldInterceptRequest`.

**Done:** `FilterEngineBehaviourTest` — 16 tests that parse the blocklist the
APK actually ships and assert on `FilterEngine.shouldBlock` with URLs in the
form a page requests them. Covers real ad hosts blocked, ordinary sites not
blocked, subdomains, lookalike domains (`notdoubleclick.net`), ports, malformed
input that must not throw on the request path, the enable switch, and the
counters the dashboard reports.

`FilterEngine` gained a package-private `loadForTest(InputStream)` and the
parse loop was extracted into a shared `parseInto`, so the test drives the
*same* parser as `loadBundled` rather than a copy of it. A test that
re-implements the loop tests the copy and passes while the original is broken.

**Verified by sabotage**, because a test that has never failed has not been
shown to work:

| Injected bug | Caught? |
|---|---|
| `[TRACKERS]` header made case-sensitive → trackers never load | ✅ 3 tests fail |
| `isBlocking()` ignores the enable switch | ✅ 1 test fails |
| host lowercasing removed from `shouldBlock` | ❌ **not caught** |

The third is recorded in the test file rather than quietly fixed: lowercasing
happens twice, so removing one is invisible. That assertion is a
characterisation test, not a guard, and it now says so. A suite that looks
stronger than it is causes worse decisions than a small one.

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

## Status

**A2, A3 and A4 are done. A1 is half done** — the code compiles and the full
suite runs (382 tests), but no APK exists and nothing has touched a device.

Phase A's purpose was to convert "this looks right" into "we watched it work".
It did that for everything reachable from a JVM, and it found three things
reading had missed: the `org.json` stubs, the wrong A3 diagnosis, and a weak
assertion in a test written this same session.

**What Phase A cannot close, and no amount of further work here will:** the
APK, the 70 MB measurement, and device behaviour. That needs a machine with the
Flutter SDK, a current JDK and more than 1 GB of RAM. Until then Tor routing,
proxy support, fingerprint defence, DNS protection and multi-profile isolation
stay 🔴 — they are now *compiled and tested* 🔴 rather than *unverified* 🔴,
which is progress but is not the same as working.

**Next:** either provision a build machine to finish A1, or start Phase B,
which is JVM-testable throughout except for its own device verification.
