# Mr Nobody Roadmap

This file records current priorities and release acceptance. General project information belongs in `README.md`; implementation history belongs in `SESSION-LOG.txt`.

**Status date:** 2026-08-22

## Current state

The main branch is green in GitHub Actions.

| Check | Current result |
|---|---|
| Flutter analysis | Passed |
| Flutter widget and screen-golden tests | 167 passed |
| Java/JVM tests | 1,028 passed |
| Privacy-auditor tests | 14 passed |
| Repository privacy audit | Clean |
| Filter digest check | Passed |
| Android Gradle unit tests | Passed |
| Signed ABI APK build | Passed |
| APK signature verification | Passed |
| 45 MiB per-ABI size gate | Passed |
| Hosted Android emulator workflow | Manual dispatch; last complete API 31/34 run passed |
| Broad physical-device matrix | Incomplete |

Automated success does not prove behavior on every Android device. System WebView versions, OEM battery policies, Android Keystore, Storage Access Framework providers, notification settings, Tor routes, and process death still require device evidence.

## What is already built

### Browser and privacy

- Flutter application shell and native Android browser core
- Android System WebView rendering
- Retained tabs and private tabs
- Local ad/tracker filtering
- Top-level redirect and popup protection
- Tracking-parameter stripping
- History disabled by default
- Third-party cookies and mixed content disabled
- Bookmarks and per-site settings
- Clear-data controls
- Normal, Private, and Nobody modes
- Proxy and Orbot support
- Bundled Tor with Orbot priority and fail-closed behavior
- Device capability reporting for WebView-dependent privacy features

### Agent

- Deterministic local search/read/answer path
- Optional Gemini, Groq, and OpenAI-compatible providers
- Pinned provider/model configuration per run
- Explicitly consented remote-provider fallback
- Deterministic local answer fallback
- Top-level skill routing plus search-specific skills
- Source evidence, citations, and figure checks
- Follow-up conversations in the same task
- Recurring checks and change detection
- Typed tool schemas and output validation
- Prompt-injection fencing
- Scoped tool access
- Approval modes and always-confirm effects
- Repeat, work, time, token, and spend budgets
- Two isolated local execution lanes

### Reliability

- Durable task rows and run IDs
- Atomic short-window task-submission deduplication
- WorkManager scheduling and startup reconciliation
- Heartbeats and stale-worker recovery
- Append-only user-facing task events
- Separate replayable execution ledger
- Idempotency identities for consequential effects
- Persistent async-job storage and polling
- Cancellable background jobs
- App-owned resumable downloads
- Foreground notifications for active tasks and downloads

### Security

- Android Keystore-backed provider credentials
- Encrypted granted account sessions
- Encrypted OAuth credential foundation
- Network egress through `NetworkGate`
- Public-network target validation for autonomous tools
- Fail-closed release signing
- Backup disabled
- Static privacy audit enforced in CI

## Current focus

MCP and online design-platform work is suspended. The next development work should focus only on the core agent, the remote worker, and general application polish.

## Priority 1 — Agent quality and reliability

### Answer quality

- Improve intent classification without adding broad, unsafe keyword matches.
- Improve source selection for factual, current, official, and technical questions.
- Make unsupported or partially supported conclusions more obvious.
- Keep citations tied only to successfully read evidence.
- Improve extractive fallback wording when remote AI providers fail.
- Test provider fallback across authentication, quota, timeout, malformed output, and cancellation failures.

### Planning

- Reduce unnecessary planning calls for simple requests.
- Expand deterministic skills only where behavior can be audited and tested.
- Keep model-selected tools restricted to the run's scope.
- Improve outcome checks for actions, downloads, named sites, and recurring work.
- Preserve run-pinned provider and platform choices after process death.

### Background execution

- Device-test two concurrent local lanes under slow HTTP, WebView extraction, downloads, and approval prompts.
- Confirm no cross-task browser, cookie, anchor, provider, guard, or budget state leaks.
- Test process death after each ledger state: prepared, running, waiting, succeeded, failed, and unknown.
- Improve user-facing recovery messages for ambiguous external outcomes.

### Agent acceptance criteria

- [ ] Local research completes reliably on API 31 and a current Android device.
- [ ] Remote-provider failure falls back without replaying tool effects.
- [ ] Back-to-back and concurrent tasks do not share state.
- [ ] A killed run resumes by replaying committed results.
- [ ] Named-site and download requests state unmet outcomes honestly.
- [ ] Recurring tasks survive process death and do not notify on unchanged results.
- [ ] Approval allow, deny, background, timeout, and restart behavior match the UI wording.

## Priority 2 — Remote worker and server persistence

The Android client protocol exists, but the production remote server does not.

### Server foundation

Build a service with:

- HTTPS task submission API
- Device public-key registration
- ECDSA request verification
- Timestamp and nonce replay protection
- Stable request idempotency keys
- Persistent task, run, step, effect, and job tables
- Transactional queue/outbox behavior
- Isolated worker processes
- Per-task cancellation and timeouts
- Durable result and event storage
- Reconnectable SSE or equivalent event streaming
- Explicit retention and deletion controls

### Execution behavior

- Reuse the same run/effect identity model as the Android client.
- Return a prior task/result for duplicate submissions.
- Never restart a completed consequential step during provider fallback.
- Persist async external job IDs before waiting.
- Represent ambiguous outcomes explicitly.
- Keep provider credentials and external-platform credentials out of prompts and logs.
- Enforce per-user work, spend, storage, and rate budgets.

### Android integration

- Add a clear per-task Local/Remote selector; Local remains the default.
- Show what data a remote worker can see before submission.
- Reconnect to running remote work after app restart.
- Let users leave and return hours or days later.
- Support cancellation without falsely reporting an unknown remote outcome as cancelled.
- Show remote status and final results in the existing task chat.

### Operations and privacy

- Define where task data is stored and for how long.
- Avoid task-content logging in infrastructure telemetry.
- Separate operational metrics from user content.
- Encrypt server-side credentials and sensitive task state.
- Add abuse prevention and queue fairness.
- Document lost-device and lost-key behavior.
- Complete a security and privacy review before public deployment.

### Remote-worker acceptance criteria

- [ ] Duplicate signed submissions create one remote task.
- [ ] A server restart does not lose queued or running work.
- [ ] A worker crash resumes from the first uncommitted step.
- [ ] Result streaming reconnects without losing terminal state.
- [ ] Cancellation has a known, persisted outcome.
- [ ] User deletion removes task content according to the published retention policy.
- [ ] Local tasks never silently move to the server.
- [ ] Server-side provider fallback does not duplicate external effects.

## Priority 3 — General application polish

### Navigation and screens

- Review all screens on small phones, large phones, font scaling, and landscape.
- Remove clipped content and unnecessary nested scrolling.
- Keep pushed routes visually consistent with shell-hosted screens.
- Improve empty states and first-run explanations.
- Make task, download, and privacy status wording shorter and clearer.

### Accessibility

- Add semantic labels to custom controls.
- Verify keyboard and switch navigation where Android supports it.
- Test large text and display scaling.
- Check contrast in Classic dark and Warm cream themes.
- Ensure status is never communicated by color alone.

### Browser polish

- Test six or more retained tabs under memory pressure.
- Improve renderer-crash recovery.
- Review back/forward/address synchronization.
- Test file chooser, camera, microphone, and location grant/deny/revoke flows.
- Verify private tabs never create previews.
- Improve controlled-fixture reporting for blocked redirects and popups.

### Task and download polish

- Make queued, running, waiting-for-approval, waiting-on-external-work, failed, cancelled, and completed states visually distinct.
- Improve long-answer reading and source navigation.
- Test follow-up suggestions with screen readers.
- Improve notification grouping and stale-notification cleanup.
- Clarify download destination, resume support, and failure causes.

### Performance

- Measure startup time without adding startup network activity.
- Measure two-lane task CPU, memory, battery, and data use.
- Measure headless WebView lifecycle and cleanup.
- Keep APKs below the current per-ABI size limit.
- Avoid retaining page bodies or image previews longer than the task needs them.

### Polish acceptance criteria

- [ ] No overflow or debug text styling on any pushed screen.
- [ ] Classic dark and Warm cream goldens are intentional and stable.
- [ ] Large text remains usable on major screens.
- [ ] Browser, agent, and download notifications clear correctly.
- [ ] Task chat explains every waiting or failed state without exposing raw internals.
- [ ] No new analytics, advertising SDK, or startup request is introduced.

## Required physical-device testing

Record device model, Android version, System WebView version, ABI, network mode, and commit for every run.

### Installation and storage

- Fresh install and relaunch
- Upgrade over an older test build
- Settings persistence
- Provider credential save/restart/remove
- Clear-data behavior
- Storage Access Framework folder grant and revocation

### Browser

- Back, forward, reload, address synchronization
- Multiple retained tabs
- Private-tab behavior
- Camera, microphone, location, and file chooser
- Controlled ad/tracker/redirect/popup fixture
- Tracking-parameter stripping compatibility

### Agent

- Local research and citations
- Named-site task
- Direct and discovered download
- Follow-up and pointer references
- Two tasks started together
- Approval while foregrounded and backgrounded
- Cancellation during search, WebView extraction, provider output, and download
- Process death at multiple ledger states
- Recurring task with changed and unchanged results

### Privacy routes

- Nobody mode with bundled Tor
- Orbot priority
- Fail-closed behavior before and during a request
- Airplane mode and captive portal
- Proxy configuration
- DNS and observed egress checks

## Deferred work

The following work is deliberately not part of the next development cycle:

- Canva MCP external acceptance
- Figma integration
- Adobe Express integration
- Other online design platforms
- Broader design editing
- Credits and payments

The repository contains design/MCP foundations, but that work resumes only after an explicit future decision.

## Later commercial work

Do not implement paid credits before the remote worker is reliable.

A future commercial layer requires:

- Append-only credit ledger
- Derived balance rather than mutable balance fields
- Idempotent purchase and debit operations
- Deterministic refunds
- Provider cost reconciliation
- Fraud and abuse controls
- Lost-device and lost-key policy
- Account recovery design
- Clear retention and deletion terms
- Independent security review

## Roadmap maintenance

- Change a status only when evidence changes it.
- Keep current priorities here, not in one-off planning files.
- Keep implementation history in `SESSION-LOG.txt`.
- Keep the repository Markdown surface limited to `README.md` and `ROADMAP.md`.
- Never call emulator or off-device tests physical-device validation.
- Record regressions and their verification before marking work complete.
