# Mr Nobody — remaining work, in three phases

Every row below was checked against the code in `app/android/`, not against
what the specs or `STATUS.md` claim. Where a doc and the code disagreed, the
code won and the disagreement is noted.

Audited at `4a9072a`.

---

## 0. What the audit changed

Five things were recorded as done or partial that are not:

| Claim | Reality |
|-------|---------|
| "Terminal gate works; CONFIRM reported to user" | **No `Confirmer` is ever attached.** `setConfirmer` is never called anywhere, and the pipeline denies when it is null. Every CONFIRM is silently refused. |
| "Downloads — Done" (V1 #12, V2) | `DownloadTool` is registered but **the planner never calls it**. Only `search` and `http` are ever invoked. Downloads work from the *browser*, never from the *agent*. |
| "APK size benchmark — Done" | CI prints the size to the step summary. **`tools/apk_size_check.py` is never called.** Nothing fails on a size regression. |
| "Scheduled tasks — not started" (accurate, but understated) | `TaskScheduler` only exposes `schedule`/`cancel` one-shot. There is no `Schedule` model, no `PeriodicWorkRequest`, no monitoring. The *interface itself* needs extending, not just an implementation. |
| "Private tabs — isolated storage" | Shared cookie jar. Corrected in `PRIVACY_V2_OPTIONS.md` §1; the fix (`MULTI_PROFILE`) is now Phase 1. |

Two things are in better shape than recorded: the **tool contract** (`ToolSpec`,
`ParamSpec`, `OutputSpec`, `Tier`) and the **guarded pipeline** (`ToolPipeline`
with `Guard`/`Approval`/`Confirmer`/`Recorder` seams) are built and tested.
Priorities 1 and 2 of the harness survey are largely done — what is missing is
the UI and the callers, not the machinery.

---

## Phase 1 — Make true what we already claim

Nothing here is a new capability. Every item closes a gap between what the
product says and what it does. This phase is the one with a correctness
argument behind it, so it goes first.

### 1.1 Honesty fixes (small, do immediately)

| # | Item | Where | Why now |
|---|------|-------|---------|
| 1 | **Enforce the APK size gate** | `.github/workflows/flutter.yml` | The script exists and is never run. One line. Set it to the 70 MB target so Phase 2's dependencies land against a real ceiling. |
| 2 | **Kill or implement `isFingerprintProtection()`** | `Settings`, `SettingsActivity` | A toggle that enforces nothing is worse than no toggle. Hide it now, implement in 1.6. |
| 3 | **Correct the private-tab wording** | UI + docs | "No history, cleared on close" until 1.4 lands. |

### 1.2 CONFIRM approval UI — the fail-closed gap ✅ DONE

`ToolPipeline` already logs the call, runs the approval waterfall, and denies
when no `Confirmer` is attached. The security design is right; there is simply
no UI on the other end, so `PolicyGate`'s entire CONFIRM tier is dead.

Build: a modal that names the tool, the tier, the reason and the exact
parameters; wire it with `setConfirmer`; persist "always allow for this tool"
as the per-tool override the survey's priority 3 calls for.

Closes: V1 §9, V2 §11, harness priority 3. Unblocks the terminal beyond
`sha256`.

### 1.3 Tool router — make "and download it" possible ✅ DONE (first rule)

The reported Reacher task could never have downloaded anything: `Task.PLAN` is
fixed at Search → Read → Answer → Verify and the planner hard-codes two tool
calls. `DownloadTool`, `BrowserTool` and `TerminalTool` are registered and
unreachable.

Build: let the planner select tools by `ToolSpec` rather than running a fixed
cascade; allow the plan to vary in length; keep the deterministic path as the
fallback when no tool matches.

Closes: V1 #12 (agent half), V2 §6, harness priority 9. **This is the largest
single item in Phase 1** and the one that turns the agent from a research
cascade into an agent.

### 1.4 Real private tabs — `MULTI_PROFILE`

Per-profile `CookieManager` and `WebStorage` via `androidx.webkit` 1.9.0.
Feature-detect; `setProfile` before first navigation; move existing cookie
policy onto the profile's manager. See `PRIVACY_V2_OPTIONS.md` §1.

Closes: V2 §10 browser session isolation (visible half), and the claim we
retracted.

### 1.5 Behavioural filter tests

The only privacy regression testing is a static grep. There are no
request-level assertions that the filter engine actually blocks anything on a
real page. This is the gap the audit fix explicitly did *not* close.

### 1.6 Fingerprint defence

`addDocumentStartJavaScript` + `setUserAgentMetadata`. Kilobytes.
`PRIVACY_V2_OPTIONS.md` §5. Ship with the honest ceiling stated in the UI.

**Phase 1 exit:** no claim in the README, the settings screen or `STATUS.md`
is unsupported by code, and CI fails when one becomes so.

---

## Phase 2 — The agent grows up

Capabilities that need Phase 1's router and approval UI underneath them.

### 2.1 Multi-step planning ✅ core done

Replace the fixed four-step cascade with a plan that can branch, loop and
replan. Requires 1.3. (V2 §5)

### 2.2 Prompt-injection defence ✅ DONE

Page text is concatenated into the prompt with **no provenance boundary**
today — a page can address the model directly. Needs: a fetched-content
boundary marker, instructions-vs-data separation, and sub-agent isolation for
untrusted pages. (V2 §24, harness priority 10)

Ranked highest-risk of everything remaining: the agent reads attacker-controlled
text and can now, after 1.3, call tools.

### 2.3 Task event log ✅ DONE

Append-only, contiguous sequence, status derived from events rather than
overwritten. `ToolPipeline.Recorder` is the seam and nothing is attached to it.
(V2 §14, harness priority 4)

### 2.4 Scheduled tasks + monitoring ✅ model done

Add a `Schedule` model and extend `TaskScheduler` beyond one-shot to
`PeriodicWorkRequest`. Then monitoring on top: price, availability, page
change. (V2 §16, §17)

### 2.5 Heartbeat and orphan recovery ✅ core done

`TaskReconciler` handles stale tasks; there is no heartbeat and cancellation
is not a persisted request. (V2 §14, harness priority 5)

### 2.6 Proxy + Tor + DNS

One feature, delivered as a mode. `ProxyController` + Orbot SOCKS on 9050,
fail-closed. DNS follows free from proxy-side resolution. **Note: the proxy is
process-wide**, so `NOBODY` is a whole-app mode, not a per-tab badge.
(`PRIVACY_V2_OPTIONS.md` §2–4, V2 §20)

### 2.7 Agent browser session isolation ✅ scope model done

One shared `HeadlessWebViewEngine` serves every task. Task-scoped profiles —
the same `MULTI_PROFILE` mechanism as 1.4, applied to the headless side.
(V2 §10)

### 2.8 Oversized output spilling + anchored page actions ✅ core done

Spill large tool output to app-private storage and hand the model a locator;
refuse a page action when the DOM has moved. (harness priorities 6, 7)

### 2.9 Security regression tests + engine reporting

Injection-resistance and policy-bypass tests; report the WebView provider and
version in the privacy dashboard so a hardened engine is visible.

**Phase 2 exit:** V2's Definition of Done is met except the items deliberately
deferred below.

---

## Phase 3 — Beyond V2

Speculative or expensive. Nothing here should start before Phase 2 lands.

### 3.1 Filter-list integrity ✅ DONE — distribution still open

Signed filter lists with rollback protection, then the decentralised
distribution experiment. Today the list is bundled and versioned with no
signature. (V2 §22, §21)

### 3.2 Agent long-term memory ✅ policy done

Task state and results persist; there is no long-term memory. Deliberately
last — it is the feature most able to turn a local-first product into a
profile of its user. (V2 §18)

### 3.3 Remote worker

`RemoteWorker` is a registered no-op. Optional, opt-in, never required.
(V2 §9)

### 3.4 Device-level test matrix

No `androidTest` exists. The privacy spec's device-level cases (network audit
under proxy, real ad-page blocking) cannot run without an emulator matrix.

### 3.5 Deferred with reasons

| Item | Why not |
|------|---------|
| Embedded Arti (Rust Tor) | Tor Project calls binary size unsolved; needs NDK per ABI. Revisit only behind ABI splits. |
| Self-resolved DoH | Reimplements HTTP/2, caching, connection reuse to gain what SOCKS5 gives free. |
| Bundling a Chromium fork | Contradicts the size budget and V2 §31's explicit non-goal. Detect and report the engine instead. |
| Rust filter core | `PrivacyEngine` is the seam if benchmarks ever justify it. They have not. |

---

## Summary

| Phase | Theme | Items | Gate |
|-------|-------|-------|------|
| **1** | Make true what we claim | 6 (**4 done**) | Every product claim is code-backed and CI-enforced |
| **2** | The agent grows up | 9 (**2 done**) | V2 Definition of Done, minus deferrals |
| **3** | Beyond V2 | 5 | Only after Phase 2 |

### Done since this roadmap was written

| Item | Commit |
|------|--------|
| 1.1 APK size gate enforced | `82074d6` |
| 1.4 Real private tabs (`MULTI_PROFILE`) | `82074d6` |
| 1.6 Fingerprint defence | `82074d6` |
| 2.6 Proxy / Tor / DNS as one mode | `82074d6` |
| 1.2 CONFIRM approval UI | this commit |
| 1.3 Tool router (first rule) | this commit |
| 2.3 Task event log | this commit |
| 2.6b Approval tiers x mode x per-tool | this commit |
| Loop breaker (harness priority 8) | `fa1bea9` |
| 2.2 Prompt-injection defence | this commit |
| 3.1 Filter-list integrity (digest + rollback) | `6af7063` |
| 2.1 Multi-step plan (`Plan`) | this commit |
| 2.4 Schedule model | this commit |
| 2.5 Heartbeat | this commit |
| 2.7 Session scope | this commit |
| 2.8 Output spilling + page anchors | this commit |
| 3.2 Memory policy | this commit |
| Budget guard (2nd monotonic guard) | this commit |

Both ordering dependencies are now discharged: the tool router exists, and the
approval UI exists to gate what it reaches.

**That makes 2.2, prompt-injection defence, the next thing to build — not the
seventh.** The reasoning has flipped from theoretical to live: page text still
reaches the model with no provenance boundary, and as of this commit the model
can reach a tool. It was survivable while the agent could only search and read.
It is not now.

Remaining in Phase 1: behavioural filter tests (1.5), and the fingerprint
toggle's honest-ceiling wording review.
