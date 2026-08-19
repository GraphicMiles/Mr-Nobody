# Mr Nobody — Roadmap

This is the single source of truth for delivery status. Implementation guidance lives in `README.md`; no additional standing specification or status documents should be added.

Audit baseline: `0afd06a` on 2026-08-19. Latest audited CI run: `32310388940`.

Status vocabulary:

- **Verified in CI** — compiled or exercised by an automated gate.
- **Built, device-unverified** — implemented and tested off-device; still needs Android hardware evidence.
- **Partial** — useful code exists, but the user path or another required component is absent.
- **Not started** — no product implementation exists.

## Executive status

The repository now passes its complete automated pipeline after the architecture and release-hardening fixes. The next phase is feature-by-feature adversarial testing on physical Android devices, with each reported defect patched and regression-tested before continuing.

Automated success does not prove System WebView, Keystore, Storage Access Framework, notifications, WorkManager, proxy/Orbot, or process-death behavior on a phone. Those remain the acceptance boundary.

## Current automated evidence

| Evidence | Result |
|---|---|
| GitHub Actions run | **Passed** — `32310388940` |
| Strict Flutter analysis | **Passed** |
| Flutter widget and golden suite | **Passed** |
| Java/JVM suite | **685 tests passed** |
| Android Gradle unit suite | **Passed** |
| Privacy-auditor suite | **13 tests passed** |
| Repository privacy audit | **Clean** |
| Filter-list validation/digest | **Passed** — `3ed6a3b8b92e…` |
| APK signatures | **Verified with APK Signature Scheme v2** |
| armeabi-v7a APK | **14.62 MiB** |
| arm64-v8a APK | **17.06 MiB** |
| x86_64 APK | **18.19 MiB** |
| APK product limit | **Passed** — 45 MiB per ABI |
| Documentation surface | **Passed** — only `README.md` and `ROADMAP.md` |
| Physical-device matrix | **Not yet completed** |

CI uses a stable, public test-only signing key so successive patched APKs can upgrade in place during device testing. Production signing requires protected external key material, never uses that public key, and never falls back to Android's debug key.

## Completed hardening work

| Finding | Resolution | Commit |
|---|---|---|
| Task ID vanished on pooled tool threads | Added explicit `TaskScope` capture/propagation/cleanup and regression tests | `ae0777a` |
| Shared mutable agent state across concurrent local tasks | Local execution is deliberately serialized; planner state is local and browser anchors reset per task | `ae0777a` |
| Provider keys and granted cookies stored as plaintext | Added Android Keystore-backed AES-GCM preferences, in-place migration, credential-removal UI, crypto tests, and privacy-audit guards | `2c20281` |
| Vulnerable unused Kotlin build plugin | Removed Kotlin plugin/options and added a CI dependency-policy gate | `7d7fcba` |
| Task timestamps replaced by read time | Restored durable `created_at`/`updated_at` values after cursor hydration | `5181464` |
| Clear-data missed isolated/live WebViews | Visible and agent WebViews are torn down before default stores and private profiles are cleared | `7266e99` |
| Fake `spill://` output locator | Replaced with a bounded, explicit, non-retrievable preview | `4eb6e08` |
| Approval “always” grant was in-memory and race-prone | Made overrides concurrent and labelled them accurately as process-session grants | `b8763d9` |
| Remote HTTP errors lost response bodies/config was stale | Added error-stream handling, disconnect guarantees, cancellation precheck, tests, and dispatch-time endpoint lookup | `6f1a55c` |
| Async `BuildContext` risks were ignored by permissive analysis | Fixed lifecycle guards and made `flutter analyze` strict | `00789cc`, `0afd06a` |
| Release build used debug signing | Added fail-closed external signing; CI uses a clearly public test key for upgradeable test builds and verifies signatures | `0be9f69` |
| Fat APK missed the 45 MiB target | Build ABI-specific APKs and enforce 45 MiB on each installable artifact | `579d376` |

## Current system status

### Browser and UI

| Capability | State | Acceptance boundary |
|---|---|---|
| Flutter shell, navigation and committed screen goldens | **Verified in CI** | Visual/gesture checks on real screen sizes |
| Visible native System WebView | **Built, device-unverified** | Navigation, renderer lifecycle, crashes and low-memory recovery |
| Retained tabs and previews | **Built, device-unverified** | Six-tab stress, close/reopen, private-tab no-thumbnail rule |
| Unified URL/search/task input | **Verified off-device** | Real keyboard, paste and deep-link behavior |
| Bookmarks and per-site controls | **Built, device-unverified** | Persistence and effective WebView behavior |
| Camera/microphone/location/file chooser | **Built, device-unverified** | Runtime grant, denial, revocation and process restart |

### Privacy and credentials

| Capability | State | Acceptance boundary |
|---|---|---|
| History and suggestions off by default | **Verified off-device** | Fresh-install observation |
| Local ad/tracker blocking | **Verified off-device** | Live request capture and counter accuracy |
| Tracking-parameter removal | **Verified off-device** | Representative live URLs and site compatibility |
| Third-party cookie/mixed-content/file-access hardening | **Built, device-unverified** | WebView inspection on minimum/current devices |
| Provider-key encryption | **Verified cryptographically off-device** | Android Keystore creation, migration and invalidation |
| Granted account-session encryption | **Verified cryptographically off-device** | Login continuity, revoke and clear-data on device |
| Private profile isolation | **Built, device-unverified** | Depends on installed System WebView multi-profile support |
| Clear browsing data | **Built, device-unverified** | Default + private + retained-tab stores must all disappear |
| Fingerprint-defense script | **Built, device-unverified** | Effective state and site breakage |
| Proxy/Orbot/Nobody route | **Built, device-unverified** | Real egress, fail-closed and DNS observations |

Privacy is not anonymity. A remote AI provider receives the task context sent to it. A future remote worker will see the URLs and page content it executes.

### Agent, tools and background work

| Capability | State | Acceptance boundary |
|---|---|---|
| Deterministic local no-model research | **Built, device-unverified** | End-to-end search, rendered extraction, evidence and answer |
| Task-scoped headless browser resolution | **Verified off-device** | Real WebView executor behavior on Android |
| Cross-task state isolation | **Verified structurally/off-device** | Back-to-back and queued task tests on a phone |
| Remote-provider autonomous planner | **Verified off-device** | Live provider/model matrix, timeout and cancellation |
| Tool schemas, approval and guards | **Verified off-device** | Foreground/background approval lifecycle |
| Honest oversized-output previews | **Verified off-device** | Large-page behavior in a live task |
| App-owned downloads | **Built, device-unverified** | SAF, MediaStore, HTTP range, process death and notifications |
| WorkManager task recovery | **Built, device-unverified** | Backgrounding, force-stop/process death and retry |
| Recurring schedules and change detection | **Built, device-unverified** | Android timing/coalescing and no-change notification suppression |
| Memory tool | **Verified off-device** | On-device persistence/forget flow |
| Terminal | **Partial** | Lightweight allowlisted runtime, not a general Android shell |
| Restricted tools | **Intentionally off** | `RestrictedTools.ACTIVE=false` |

### Remote execution and commercial layer

| Capability | State | Note |
|---|---|---|
| Android installation identity | **Partial** | Pure signing model tested; Keystore behavior needs hardware |
| Signed request envelope | **Verified off-device** | Freshness, integrity and signature tests |
| Remote transport client | **Partial** | Hardened and tested, but no deployed service here |
| Remote task selection | **Not a production path** | New tasks remain local by default and no user-facing selector is shipped |
| Remote server | **Not started in this repository** | No verifier, queue, isolated worker or result service |
| Credits/payments | **Not started** | No ledger, purchase, refund or lost-key policy |

Remote and credits remain out of the local-device acceptance phase.

## Next phase — Feature-by-feature device adversarial testing

### Testing rules

1. Test one feature group at a time.
2. Report the first reproducible defect before moving to the next group.
3. Patch the smallest root cause, add an automated regression test where possible, run CI, then repeat the same device scenario.
4. Record device model, Android version, System WebView provider/version, ABI and tested commit.
5. Never paste real API keys, cookies, passwords, authorization headers or private URLs into chat. Replace them with `<redacted>`.
6. A feature is not marked verified merely because it worked once; include a failure/denial/restart case where relevant.

### Bug report template

```text
Feature:
Build commit:
Device / Android version:
System WebView version:
Network mode:
Preconditions:
Exact steps:
Expected:
Actual:
Reproducibility: always / intermittent / once
Error shown:
Relevant debug-log lines (secrets redacted):
Screenshot or screen recording:
```

### Test order

#### 1. Install, launch and persistence

- Install the APK matching the device ABI.
- Confirm first launch and relaunch.
- Change harmless settings, kill/reopen, and confirm persistence.
- Upgrade over an older build if available.
- Confirm application version and engine information are visible and accurate.

#### 2. Credential storage and removal

- Save a disposable provider key, restart, and confirm only “key present” is exposed.
- Remove the key and confirm the active provider returns to Local.
- If testing migration, use a disposable legacy key and confirm it still works after upgrade.
- Grant a disposable test-site session, restart, revoke it, and confirm access disappears.
- Do not inspect or share real credential values; use a test account.

#### 3. Visible browser and tabs

- Navigate, back, forward, reload and open external/deep links.
- Open six tabs, switch repeatedly, background/reopen, then close each.
- Confirm normal tab pages retain state within the retention limit.
- Confirm private tabs never produce thumbnails.

#### 4. Clear browsing data and private isolation

- Create normal and private cookies/storage on a test site.
- Clear cookies/site data/cache.
- Reopen normal and private pages and confirm every selected bucket is gone.
- Where multi-profile is supported, prove normal and private sessions cannot see each other.
- Repeat when multi-profile is reported unavailable; wording must downgrade honestly.

#### 5. Blocking and URL privacy

- Load pages that request known bundled ad/tracker domains.
- Confirm requests are blocked before leaving, and counters match.
- Disable blocking and confirm behavior changes; re-enable it.
- Test tracking-parameter removal with ordinary and signed URLs to catch breakage.

#### 6. Local no-model agent

- Run an ordinary research request.
- Run a named-site request.
- Use “open the second one” and confirm it uses the existing artifact.
- Start two tasks quickly; confirm one queues and neither inherits the other's site/session/anchor.
- Use a large page and confirm the answer reports a non-retrievable preview rather than a fake locator.

#### 7. Approval and action safety

- Trigger an action requiring confirmation.
- Deny it and prove no action occurred.
- Allow once and complete it.
- Select “allow for this app session,” repeat, then restart the app and confirm prompting returns.
- Trigger approval while the app is backgrounded; the task must park rather than run.

#### 8. Downloads

- Download to public Downloads and a selected SAF folder.
- Pause, resume and cancel.
- Resume against a server that supports ranges and one that does not.
- Kill the process mid-download and verify the row becomes recoverable.
- Exercise notification actions and file opening.
- Uninstall only with disposable files and verify app-owned behavior.

#### 9. Background tasks and schedules

- Background during a running task.
- Kill the process and observe recovery/reconciliation.
- Cancel queued and running tasks.
- Run a recurring monitor and verify unchanged results do not notify repeatedly.
- Verify task creation/update timestamps remain stable after reopening.

#### 10. Remote AI providers

- Test Gemini, Groq and one OpenAI-compatible endpoint with disposable keys.
- Fetch the model list, select a valid model, stream an answer, cancel and force a timeout.
- Confirm task context disclosure is visible.
- Confirm removing a key falls back to Local and the provider cannot run.

#### 11. Privacy routes

- Configure an HTTP proxy and observe egress.
- Run Nobody mode with Orbot active.
- Stop Orbot before and during a request; traffic must fail closed.
- Observe DNS behavior rather than inferring it from SOCKS configuration.
- Confirm the UI reports the effective mode, not merely the requested mode.

#### 12. Permissions and edge cases

- Allow and deny camera, microphone, location, notifications and file selection.
- Revoke permissions in Android Settings and retry.
- Test offline startup, captive portal, slow network, rotation and low-memory backgrounding.
- Enter malformed URLs, very long instructions, unsupported files and interrupted provider responses.

## Device-phase exit criteria

- [ ] API 31 physical device matrix completed.
- [ ] Current Android physical device matrix completed.
- [ ] Task-scoped browser execution works end to end.
- [ ] Back-to-back tasks show no state/session/anchor leakage.
- [ ] Provider keys and granted sessions survive encrypted migration and can be removed.
- [ ] Clear data removes normal/private/retained browsing state.
- [ ] Local research, follow-up and download paths complete on-device.
- [ ] Approval deny/allow/session-reset behavior matches wording.
- [ ] Downloads survive supported lifecycle cases without corruption.
- [ ] WorkManager recovery, cancellation and recurring schedules behave correctly.
- [ ] Proxy/Orbot fail-closed and DNS behavior are observed.
- [ ] Every found defect has a regression test or a recorded device test where automation is impossible.
- [ ] CI remains green after every patch.

## Later phases

### Remote execution end to end

- Deploy server-side signature verification and replay protection.
- Add queue/worker isolation, cancellation, timeouts and reconnectable event streaming.
- Add an explicit per-task local/remote selector; local remains default.
- Define retention and observability without task-content logging.
- Complete device-to-server adversarial testing.

### Credits and paid operation

- Append-only credit ledger with derived balance
- Idempotent purchases/debits and deterministic refunds
- Measured cost/capacity model
- Lost-device/lost-key policy stated before purchase
- Payment reconciliation and abuse controls
- Security/privacy review of the complete service

Do not sell credits before remote execution is reliable and the lost-key policy is explicit.

## Roadmap maintenance

- Change a status only when evidence changes it.
- Include commit, CI run or device evidence for “verified” claims.
- Keep developer guidance in `README.md` and delivery status here.
- Do not add separate status, milestone, architecture or specification documents.
