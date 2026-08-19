# Mr Nobody — Roadmap

This document is the single source of truth for delivery status and planned work. The production-code audit baseline is commit `21a051f` from 2026-08-19. Documentation cleanup after that baseline does not change runtime behavior.

Status vocabulary:

- **Verified** — exercised by the stated automated gate.
- **Built, device-unverified** — implemented and covered off-device, but not signed off on physical Android hardware.
- **Partial** — useful code exists, but the user path or an essential component is missing.
- **Not started** — no product implementation exists.

## Executive status

Mr Nobody has a broad, compiling Android/Flutter implementation and a green CI pipeline. The next milestone is not another feature wave. It is to make the existing local product trustworthy on hardware, isolate task execution correctly, secure release credentials, and produce a production-signable artifact within the size target.

### Audited evidence

| Evidence | Result |
|---|---|
| Latest audited GitHub Actions run for `21a051f` | **Passed** (`32242714741`) |
| Flutter analysis | **Passed in CI** |
| Flutter widget and golden tests | **Passed in CI** |
| Fast Java/JVM suite | **675 tests passed locally** |
| Android Gradle unit suite | **Passed in CI** |
| Privacy-auditor self-tests | **11 tests passed locally and in CI** |
| Repository privacy audit | **Clean** |
| Filter-list digest check | **Passed** (`3ed6a3b8b92e…`) |
| Release APK build | **Passed in CI** |
| APK artifact | **48,370,906 bytes / 46.13 MiB** |
| Hard APK gate | **Passed** (70 MiB ceiling) |
| Product APK target | **Missed** (target ≤45 MiB) |
| Physical-device acceptance matrix | **Not run / no recorded evidence** |

The APK exists as a CI artifact, correcting older notes that claimed no APK had been built. It remains a test artifact because the Gradle release build uses debug signing.

## Where we stand

### Browser and UI

| Capability | State | Evidence / limit |
|---|---|---|
| Flutter application shell and navigation | **Verified in CI** | Widget/golden suite covers launch, home, browser, tabs, privacy, tasks, providers, settings, clear-data, and downloads surfaces. |
| Visible System WebView | **Built, device-unverified** | Native platform view in `MrNobodyWebView`; no hardware navigation session recorded. |
| Retained tabs and previews | **Built, device-unverified** | `TabWebViews` retains native WebViews; lifecycle behavior still needs hardware stress. |
| URL/search/task unified input | **Verified off-device** | Dart and Java routing tests exist. |
| Deep links | **Verified off-device** | Pure-Java handler tests plus Flutter route wiring. |
| Bookmarks and per-site settings | **Built, device-unverified** | Native stores and bridge/UI paths exist. |
| Camera/microphone/location web permissions | **Built, device-unverified** | Manifest and permission store exist; real prompt/grant/revoke behavior needs a device. |

### Privacy

| Capability | State | Evidence / limit |
|---|---|---|
| History off by default | **Verified off-device** | `Settings` default and privacy tests. |
| Ad/tracker blocking | **Verified off-device** | Request matcher, real bundled-list behavior tests, visible-WebView interception wiring, and digest pin. No live page capture on hardware yet. |
| Tracking-parameter removal | **Verified off-device** | Unit tests for known parameters and safe URLs. |
| Third-party cookie/mixed-content/file-access hardening | **Built, device-unverified** | Applied in native WebView settings; hardware inspection remains. |
| Private WebView profile isolation | **Built, device-unverified** | Uses AndroidX WebKit multi-profile support when available; capability varies by installed WebView. |
| Fingerprint-defense script | **Built, device-unverified** | Feature-detected and setting-controlled; effect and site compatibility are not measured. |
| Proxy route | **Built, device-unverified** | Native URL connections and WebView proxy override are wired; no observed egress test. |
| Orbot/Nobody fail-closed route | **Built, device-unverified** | Loopback SOCKS route, probe, and network gate are unit-tested; no real Orbot run recorded. |
| DNS-through-SOCKS claim | **Device-unverified** | Must be proven with an egress/DNS observation, not inferred from configuration. |
| No analytics/ad SDK | **Verified statically** | Privacy audit and dependency review. |

Privacy is not anonymity. Normal browsing exposes traffic to the selected network route and destination. A remote AI provider sees the task context sent to it. A future remote worker will see task URLs and page content because it executes the task.

### Agent and task system

| Capability | State | Evidence / limit |
|---|---|---|
| Deterministic local research | **Built, device-unverified** | Search/read/extractive-answer/evidence logic has JVM coverage; end-to-end headless WebView use on a phone is not proven. |
| Remote-provider autonomous planner | **Verified off-device** | Step codec, planner loop, tool routing, grounding, figure checks, and budgets have tests; live provider/device matrix remains. |
| Named-site handling and follow-ups | **Verified off-device** | Named source, site skill, artifact pointer, and resolver tests. |
| Tool contracts and guarded pipeline | **Verified off-device, with spill gap** | Schema, output, tier, confirmation, repeat, work, timeout, preview, and security regression tests. Full spilled output is not persisted. |
| Approval UI | **Built, device-unverified** | Foreground confirmation path and parked-task behavior need lifecycle testing. |
| App-owned downloads | **Built, device-unverified** | Range/resume/naming/persistence logic is tested; real SAF/MediaStore/notification/pause/resume is not signed off. |
| Task persistence and WorkManager | **Built, device-unverified** | SQLite migrations, retry, heartbeat, reconciliation, cancellation, and event logic have tests. |
| Recurring schedules/monitoring | **Built, device-unverified** | Periodic WorkManager scheduling and change detection exist; timing and process-death behavior need hardware observation. |
| Memory tool | **Verified off-device** | Local ranking/digest/policy tests; no cross-device sync. |
| Terminal | **Partial** | Feature-flagged, policy-gated lightweight runtime. It is not a general Android shell and toolchain commands may require a future remote worker. |
| Restricted tools | **Intentionally off** | `RestrictedTools.ACTIVE=false`; no active payload. |

### Remote execution and commercial layer

| Capability | State | Evidence / limit |
|---|---|---|
| Installation identity | **Partial** | P-256 identity/signature logic is tested; Android Keystore generation/security-level reporting needs a device. |
| Signed request envelope | **Verified off-device** | Freshness, integrity, and signature tests exist. |
| Remote client | **Partial** | Submit/SSE client and tests exist. Error, cancellation, reconnect, and deployment behavior need end-to-end work. |
| Remote worker selection | **Not reachable as a product path** | New tasks are inserted with `worker=local`; there is no production UI/API that switches a task to remote. |
| Remote server | **Not started in this repository** | No deployed verifier, queue, browser worker, or result service. |
| Credits/payments | **Not started** | No ledger, pricing enforcement, purchase flow, refund/lost-key policy, or payment integration. |

## Audit findings that set the next phase

### P0 — Task context does not safely cross the tool executor

`LocalWorker` binds the task ID with `EventLogRecorder`'s `ThreadLocal`. `ToolPipeline` then runs each tool body on a cached executor thread. A supplier such as `HeadlessSessions::current` resolves on that executor thread, where the binding is absent. The production headless `BrowserTool`/search escalation can therefore see no task-scoped browser even though the worker acquired one.

This must be fixed before claiming the local agent works on a phone. The task/session identity must be captured explicitly with the call or execution context; replacing the value with another ambient thread-local is not sufficient unless propagation through pooled threads is guaranteed and tested.

### P0 — Singleton agent state is not isolated between concurrent tasks

`MrNobodyApp` owns one `DeterministicEngine`, one `RepeatCallGuard`, one `BudgetGuard`, and shared tool instances. Every run resets shared guard state. `BrowserTool.lastKnownUrl` is also shared. WorkManager may execute more than one task, so one run can reset, consume, or overwrite another run's state.

Per-run mutable state must move into a task execution context or a fresh engine/pipeline/tool set. Add a deterministic concurrent-task regression test before enabling parallel work.

### P0 — Secrets are app-private but not encrypted at rest

Provider keys are written to ordinary `SharedPreferences`. `allowBackup=false` limits exposure, but “stored locally” is not the same as Keystore-backed encryption. Migrate keys to an Android Keystore-protected store, test migration/removal, and ensure diagnostics/logs never expose values.

### P0 — Release output is not production-signed

`app/android/app/build.gradle` points the release build at `signingConfigs.debug`. Introduce secret-backed release signing and separate unsigned/test artifacts from production release candidates. No signing material may enter the repository.

### P1 — The APK exceeds the stated product target

The audited APK is 46.13 MiB, 1.13 MiB above the 45 MiB target, while CI only fails above 70 MiB. Profile the artifact, remove avoidable weight, and either return below 45 MiB or explicitly revise the target with measured rationale. Do not present the 70 MiB emergency ceiling as the target.

### P1 — Oversized-output “spill” has no backing store

`ToolPipeline` creates a `spill://` locator and tells the planner that the full output is kept, but `OutputSpill` is decision logic only and no component writes the full value. The locator cannot be resolved. Either persist and expose bounded task-private retrieval, or change the result to an honest truncation/refusal. The current message must not claim retained data.

### P1 — Device behavior remains inference

The CI system proves compilation and off-device logic. It does not prove WebView lifecycle, real network routing, Android Keystore behavior, SAF writes, MediaStore publication, notification actions, process-death recovery, or Orbot fail-closed behavior. A repeatable device matrix is required.

### P2 — Remote client code is ahead of its product path

Remote identity/client classes exist, but task creation forces local execution and no server is deployed here. Keep the path disabled until the local phase exits; then build server verification and task selection as one end-to-end milestone instead of accumulating more unreachable client code.

## Next phase — Local truth and release hardening

**Goal:** produce a production-signable local build whose core browser, agent, privacy, download, and background behaviors have been observed on supported hardware, with task isolation and secret storage corrected.

### Workstream 1 — Correct execution isolation

1. Introduce an explicit `TaskExecutionContext` containing task ID, task-scoped browser/session, cancellation, event recorder, and per-run budgets.
2. Pass that context into tool execution; do not resolve security-critical context from an unpropagated pooled-thread `ThreadLocal`.
3. Give each running task its own repeat guard, work budget, plan state, spend state, and browser anchor state.
4. Keep immutable tool specs shareable, but make mutable tool execution state per task.
5. Define the concurrency policy. If multiple local headless WebViews are unsafe on a target device, serialize local agent browser work deliberately and expose queued state instead of relying on accidental singleton behavior.
6. Back oversized-output locators with bounded task-private storage and a retrieval path, or replace the locator with an honest non-retrievable truncation.
7. Add regression tests for:
   - production supplier resolution through `ToolPipeline`'s executor;
   - two tasks reading different browser sessions concurrently;
   - one task resetting/using budgets without affecting another;
   - different anchor URLs remaining task-local;
   - cancellation and event attribution under concurrency.

**Exit gate:** two simultaneous tasks cannot share cookies, browser anchors, guard counters, plan state, event IDs, or cancellation state.

### Workstream 2 — Secure local secrets

1. Store AI-provider keys using Android Keystore-backed encryption.
2. Migrate existing plaintext preferences once, then remove the old values.
3. Add “remove key” behavior that deletes encrypted material and deactivates the provider if required.
4. Verify keys never appear in diagnostics, task events, crash logs, memory summaries, or exception strings.
5. Add migration, unavailable-Keystore, corrupt-ciphertext, key-deletion, and credential-redaction tests.

**Exit gate:** a filesystem copy of app preferences does not contain usable provider keys.

### Workstream 3 — Build the hardware acceptance matrix

Test at minimum:

- Android 12 / API 31 with an older supported System WebView
- A current Android release with a current System WebView
- One low-memory physical device
- Orbot installed/running and absent/stopped cases

Required scenarios:

1. Fresh install, first launch, settings persistence, upgrade from the current SQLite schemas, and clear-data behavior.
2. Visible browse, back/forward/reload, six retained tabs, close/reopen, deep links, permissions, and file chooser.
3. Blocking against known ad/tracker requests, counters, blocklist-integrity failure, and blocking toggle.
4. Local no-model tasks: ordinary research, a named-site request, “open the second one,” and a resolved download.
5. Remote-provider task: model selection, streamed answer, source/figure warning, cancellation, provider timeout, and spend reporting.
6. Download to public Downloads and a SAF folder; pause, resume, cancel, process death, notification actions, and uninstall ownership.
7. Task backgrounding, process death, retry, heartbeat reconciliation, approval while app is absent, recurring task, and no-change notification suppression.
8. Private tabs with and without multi-profile support; verify cookies/storage do not cross when isolation is reported available.
9. Fingerprint protection on/off and representative site compatibility.
10. HTTP/SOCKS proxy and Nobody/Orbot egress; stop Orbot mid-session and observe fail-closed behavior.
11. DNS observation through the selected route before making any DNS-protection claim.
12. Android Keystore identity creation and reported hardware/software security level.

Record device model, Android version, System WebView provider/version, test build SHA, result, and evidence for every run.

**Exit gate:** all P0 scenarios pass on the minimum API and a current device, with no silent capability downgrade.

### Workstream 4 — Release engineering and size

1. Add environment/secret-backed release signing; keep debug signing limited to debug/test builds.
2. Define version bump and changelog text inside the release process without creating another standing documentation file.
3. Add an explicit CI job for a production-signable release candidate or an unsigned artifact intended for controlled signing.
4. Generate and retain checksums/provenance for release artifacts.
5. Profile APK contents and reduce the audited 46.13 MiB artifact below 45 MiB.
6. Tighten the warning threshold near the product target while retaining a hard emergency ceiling.
7. Verify install/upgrade with the signed candidate.

**Exit gate:** reproducible signed candidate, install/upgrade verified, APK ≤45 MiB, and no signing secret in source or logs.

### Workstream 5 — Claim and UI truth pass

1. Ensure every privacy screen reports **supported** and **enabled** separately.
2. Mark device-dependent features unavailable when the installed WebView lacks them.
3. Keep “Local (no model)” wording anywhere the deterministic provider appears.
4. State that remote AI providers receive task context.
5. State that Nobody mode is whole-app/process-wide where the WebView proxy override is process-wide.
6. Remove or disable any control that cannot affect runtime behavior.
7. Ensure diagnostics expose effective route/capabilities without creating an identity or making a network request.

**Exit gate:** no user-facing claim is stronger than the measured effective state.

## Next-phase completion criteria

The phase is complete only when all of the following are true:

- [ ] Task context reaches tool executor threads explicitly and is regression-tested.
- [ ] Per-task mutable engine/guard/browser state is isolated under concurrent work.
- [ ] Oversized-output behavior is backed by real bounded storage/retrieval or no longer claims retention.
- [ ] Provider keys are encrypted with Keystore-backed storage and migrated safely.
- [ ] Release builds no longer use debug signing.
- [ ] The release APK is at or below 45 MiB.
- [ ] The full hardware matrix is recorded for API 31 and a current Android device.
- [ ] Local research, headless browser escalation, named-site flow, follow-up, and download are observed end to end.
- [ ] Downloads, notifications, WorkManager recovery, recurring work, and approvals survive lifecycle tests.
- [ ] Private-profile and fingerprint capabilities are reported and verified accurately.
- [ ] Proxy/Orbot fail-closed and DNS behavior are observed, not inferred.
- [ ] CI remains green across Flutter, JVM, Gradle, privacy, filter, and size gates.
- [ ] README and in-app wording match the measured results.

## Later phases

### Phase after next — Remote execution end to end

Start only after the local-truth phase exits.

- Define and version the remote protocol.
- Build server-side signature verification, nonce/replay storage, task queue, worker isolation, cancellation, timeouts, and SSE replay/reconnect.
- Fix non-2xx response handling and define stable structured errors.
- Add a deliberate task-level local/remote selector; never silently move a local task.
- Rebuild/register the remote worker when server settings change.
- Add device-to-server end-to-end tests, abuse/rate limits, observability without task-content logging, and a deletion/retention policy.
- Make the privacy disclosure explicit: the remote service sees the work it executes.

**Exit gate:** a user can opt one task into remote execution, observe it complete/cancel/reconnect, and verify that local remains the default.

### Following phase — Credits and paid operation

- Append-only credit ledger with derived balance
- Idempotent purchase and debit operations
- Measured cost model and enforceable per-task limits
- Refund rules for failed/cancelled tasks
- Lost-device/lost-key policy stated before purchase
- Payment provider integration and reconciliation
- Capacity, queue, and abuse controls
- Security and privacy review of the complete service

Do not sell credits before remote execution is reliable and the lost-key policy is explicit.

### Deferred unless evidence changes

- Bundling GeckoView or another browser engine: reconsider only if measured privacy/capability gains justify size and maintenance cost.
- On-device general-purpose VM/browser sandbox: too heavy for the current product target.
- Unofficial account-login libraries, stealth challenge bypass, site-specific pirate downloaders, and third-party reader-by-default paths: remain restricted/off.
- Claims that a remote executor cannot read task content: technically false and permanently rejected.

## Roadmap maintenance

- Update this file when evidence changes state, not when code merely lands.
- Include commit/run/device evidence for “verified” claims.
- Keep implementation guidance in `README.md`; keep delivery status here.
- Do not add separate status, milestone, phase, architecture, or specification documents. Consolidate durable information into the two repository documents.
