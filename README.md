# Mr Nobody — Developer Guide

Mr Nobody is an Android 12+ privacy browser with an on-device task agent. Flutter renders the application chrome; a native Java core owns browsing, filtering, downloads, task execution, persistence, privacy controls, and AI-provider integration.

This is the repository's developer document. Product status and the next delivery phase are tracked in [ROADMAP.md](ROADMAP.md).

> **Accuracy boundary:** this guide describes the current source tree. A passing unit test or CI build is not the same as successful operation on physical hardware. Device-validation gaps are called out in the roadmap.

## Current snapshot

Production-code audit baseline: `0afd06a` on 2026-08-19.

- App version: `1.0.0+1`
- Android application ID: `com.mrnobody.browser`
- Minimum Android version: API 31 / Android 12
- UI: Flutter 3.24.5 in CI, Dart SDK constraint `^3.5.4`
- Native core: Java 11 bytecode, built with JDK 17 in CI
- Rendering engine: the installed Android System WebView; no browser engine is bundled
- Latest audited CI run: `32390059362`, successful across strict analysis, Flutter tests, JVM/Gradle tests, privacy checks, signed APK build, signature verification, and size gates; hosted emulator smoke run `32390059414` passed on API 31 and API 34
- ABI-specific CI APKs: armeabi-v7a 14.62 MiB, arm64-v8a 17.06 MiB, x86_64 18.19 MiB; every artifact is below the 45 MiB limit
- Java/JVM suite: 759 tests passing at the current head
- Python privacy-auditor suite: 13 tests passing

The source contains substantial Android functionality, but the complete local-agent, WebView, background-work, download, and privacy-route flows have not yet been signed off on a physical device. Do not convert “implemented and tested off-device” into a runtime claim.

## Product boundaries

### What is implemented

- Visible browsing through a native platform-hosted System WebView
- Multiple retained tabs, private-tab support when the installed WebView exposes multi-profile APIs, bookmarks, deep links, and per-site controls
- On-device ad/tracker filtering for subresources and top-level navigations, exact-domain cross-site betting-redirect protection, popup/pop-under suppression, URL tracking-parameter stripping, local block counters, and a pinned bundled-list digest
- History disabled by default, third-party cookies disabled, mixed content blocked, file/content access disabled, and backup disabled
- App-owned downloads with destination selection, persistence, pause/resume/cancel, HTTP range validation, foreground-service support, and notifications
- Persistent tasks backed by SQLite and Android WorkManager, including retries, cancellation, heartbeat/reconciliation, event logs, follow-ups, recurring schedules, and restored durable timestamps
- Explicit task-scope propagation onto tool executor threads; local agent runs are deliberately serialized so mutable planner/tool state cannot cross tasks
- Keystore-backed AES-GCM storage for provider API keys and user-granted account sessions, including plaintext migration and key removal
- A local deterministic research path that searches, reads sources, produces an extractive answer, records evidence, and verifies citations/figures
- Optional remote AI providers: Gemini, Groq, and an OpenAI-compatible endpoint
- An autonomous observe/reason/act planner when a remote AI provider is selected
- Typed tool contracts, tiered approval, human confirmation, repeat/work/spend budgets, oversized-output preview decisions, prompt-injection fencing, and page anchors
- Search, HTTP, browser, download, memory, and optional terminal tools
- Normal, Private, and Nobody privacy modes, including proxy/Orbot route code and device capability reporting
- A client-side remote-worker protocol with Android Keystore identity, signed requests, and SSE result streaming

### What is not a completed product path

- Physical-device validation is incomplete.
- The remote-worker client has no deployed server in this repository and normal task creation currently persists `worker=local`; remote execution is therefore not an end-to-end user path.
- Credits, payments, account recovery, and a remote credit ledger are not implemented.
- Production release signing requires externally supplied protected key material. CI uses a stable, public test-only key so patched builds can upgrade in place; its APKs are explicitly test artifacts.
- Several WebView privacy capabilities depend on the installed WebView version and can legitimately be unavailable on a device.
- Oversized tool output is intentionally reduced to an honest, non-retrievable preview; no synthetic storage locator is advertised.
- “Local” means a deterministic, no-model research path. It must not be described as an on-device LLM.
- Privacy controls reduce browser-controlled tracking; they do not make a user anonymous. Nobody mode depends on a configured, available proxy or Orbot route.
- Restricted tools shown in Settings are compiled off through `RestrictedTools.ACTIVE = false` and have no active payload.

## Repository layout

```text
.
├── .github/workflows/flutter.yml       CI build and verification pipeline
├── app/
│   ├── lib/                            Flutter UI, navigation, state, and bridge wrappers
│   ├── android/app/src/main/java/      Native browser and agent core
│   ├── android/app/src/test/java/      JVM/Android unit tests
│   ├── test/                           Flutter widget and golden tests
│   └── pubspec.yaml                    Flutter package and app version
├── filters/bundled/blocklist.txt       Source copy of the bundled filter list
├── tools/                              Test, privacy, filter, icon, and APK-size tooling
├── LICENSE                             MIT license
├── README.md                           This developer guide
└── ROADMAP.md                          Current status and planned phases
```

The APK ships `app/android/app/src/main/assets/blocklist.txt`. Keep it byte-identical to `filters/bundled/blocklist.txt`, and update the digest pin only through the documented tooling/checks.

## Architecture

### Process split

Flutter owns presentation. Java owns application state and all security-sensitive behavior.

```text
Flutter screens and widgets
        │
        │ MethodChannel: mrnobody/core
        ▼
MainActivity ─────────────── MrNobodyApp singletons
        │                         │
        │                         ├─ browser/privacy/settings stores
        │                         ├─ task store, scheduler, dispatcher
        │                         └─ agent engine and guarded tool pipeline
        │
        └─ native PlatformView: MrNobodyWebView
                                  │
                                  ├─ System WebView rendering
                                  ├─ request interception/filtering
                                  ├─ cookie and storage policy
                                  └─ app-owned download handoff
```

Do not move filtering or browser security decisions into Dart. Subresource requests must be rejected synchronously on the native WebView request path.

### Important entry points

| Area | Entry point | Responsibility |
|---|---|---|
| Flutter application | `app/lib/main.dart` | Shell navigation, deep-link routing, tabs, tasks, and screens |
| Flutter/native bridge | `app/lib/bridge/native_bridge.dart` | Typed wrappers over `mrnobody/core` |
| Android bridge | `app/android/app/src/main/java/com/mrnobody/browser/MainActivity.java` | MethodChannel implementation and platform-view registration |
| Application bootstrap | `.../browser/MrNobodyApp.java` | Long-lived stores, privacy state, providers, tools, workers, and scheduler |
| Visible browser | `.../browser/webview/MrNobodyWebView.java` | WebView policy, request blocking, navigation events, and downloads |
| Agent engine | `.../agent/planner/DeterministicEngine.java` | Local research and remote-provider autonomous execution |
| Tool security | `.../agent/core/ToolPipeline.java` | Schema validation, policy, guards, confirmation, timeout, output validation, and preview limiting |
| Task persistence | `.../agent/tasks/TaskStore.java` | Durable task state and schema migrations |
| Task event contract | `.../agent/tasks/TaskEventDetail.java` | Versioned semantic activities and bounded tool-attempt/outcome metadata |
| Adaptive task UI | `app/lib/agent/task_timeline.dart` | Projects the current run’s real events into activities, metrics, recovery states, and read sources |
| Background execution | `.../agent/tasks/TaskWorker.java` | WorkManager dispatch, heartbeat, cancellation, retry, and notifications |
| Downloads | `.../browser/download/DownloadEngine.java` | Transfer lifecycle and persistence |
| Network boundary | `.../browser/net/NetworkGate.java` | Single native HTTP egress gate for agent/provider/download traffic |
| Privacy routing | `.../browser/net/PrivacyController.java` | Normal/Private/Nobody route application and fail-closed behavior |

### Unified input flow

1. `IntentRouter` classifies input as a URL, search, or task.
2. URLs/searches open the visible browser.
3. Tasks are inserted into `TaskStore` and scheduled with WorkManager.
4. `TaskWorker` reloads the task and calls `TaskDispatcher`.
5. The local worker binds task context, acquires a task-scoped headless browser, and invokes the agent engine.
6. Every tool call goes through `ToolPipeline` before execution.
7. Task state and events are persisted and streamed back to the Flutter task chat.

`TaskScope` captures the task ID before a tool enters the shared executor and clears it after the call. The local worker also serializes runs and resets browser anchor state per task. Active WorkManager runs are promoted with a low-importance `dataSync` foreground notification before web work begins, so switching away does not demote a user-started search or rendered read. This removes pooled-thread context loss and prevents concurrent tasks from sharing mutable planner, guard, or browser state; the end-to-end Android behavior remains part of device testing.

### Adaptive agent response contract

The task chat has a stable visual grammar, not a fixed task template:

- The engine appends a semantic `step.changed` event only when it actually enters an activity selected by the deterministic or autonomous planner.
- Tool attempts and outcomes use versioned JSON metadata linked by call ID. Page bodies, prompts, form text and arbitrary tool output are not copied into the event log.
- `TaskTimeline` scopes each execution cycle independently. The active turn never inherits prior work, while every completed answer retains its own collapsed “Thought for …” trace, citations, source controls and captured evidence cards in thread history.
- Standalone follow-up questions use their own search terms; only explicit references such as “why?”, “download it” or “compare the sources” receive thread context. Greetings and acknowledgements finish locally without invoking tools.
- Grounded research and download turns may expose context-derived follow-up suggestions; direct replies and failed/ungrounded turns do not receive a seeded suggestion section.
- Tool calls are subordinate evidence beneath an agent-owned activity. A failed direct read followed by a successful rendered read becomes `recovered`; the failed tool does not replace the task hierarchy.
- Status, encountered/used counts and duration sit beneath the activity verb. Raw call syntax is available only as bounded detail.
- Answer headings, paragraphs, lists, citations, evidence cards, approval prompts, errors and source controls are conditional. Search candidates are not shown as evidence unless that page was successfully read.
- Script/configuration dumps are rejected before they can become evidence. Specialised latest-YouTube requests use a site-restricted result and listing metadata instead of treating a watch-page application shell as article prose.
- Numbered citations resolve against successful read order, and source controls show cited/linked evidence rather than every attempted read. A task with no sources, cards, approval or artifacts does not receive those sections merely to fill a preset.
- Legacy plain-text event details remain readable, but new work must emit the structured contract.

The model may choose proposed actions and answer structure; it does not choose arbitrary widgets or bypass rendering policy. The application maps validated typed outcomes to approved components so consistency does not become seeded content.

### Search skill routing

`SearchSkills` is the deterministic extension point for task-specific search behaviour. A skill may shape the query, honour an explicitly requested provider, constrain acceptable result hosts, decide whether results are listing metadata or readable evidence, and add an honest limitation note. It cannot execute a request directly; the resulting Search call still enters `ToolPipeline`.

Built-in routes currently include:

- `youtube.latest` — channel-restricted latest-video query, one selected watch result, and no watch-page JavaScript extraction.
- `youtube.search` — public YouTube watch-result discovery.
- `information.latest` — current-year query bias followed by ordinary source reads and verification.
- `facebook.public_search` — only public, search-indexed Facebook pages/posts; no private profile, group or login bypass.
- `material.search` — learning resources and direct-document discovery; adds `filetype:pdf` when appropriate and uses Google only when the instruction explicitly asks for Google search.
- `research.academic` — scholarly-paper discovery across arXiv, PubMed and DOI-indexed results, with publication/retraction/access caveats.
- `documentation.official` — official API and technical documentation with normal page reading.
- `information.news` — dated current-news research and source comparison.
- `research.fact_check` — independent fact checks plus primary-evidence query shaping.
- `information.finance` and `information.weather` — date-sensitive figures/conditions that require fresh source reads.
- `research.government` — official public records, regulations, reports and statistics.
- `web.general` — the evidence-first fallback for everything that does not match a specialised route.

The router is ordered from most specific to general, so overlapping language such as “latest YouTube video” cannot fall into generic freshness. Host-constrained skills fail rather than substituting unrelated sites. Tests cover overlap, malformed/empty results, provider forcing, private-social limitations, duplicate hosts, code/configuration dumps and fallback behaviour.

While a task is live, the current activity automatically expands its bounded Decision/Outcome/tool preview. When the next activity starts, the completed stage collapses to its status/metric/duration summary; every stage remains manually reopenable.

### Agent modes

**Local provider**

- Makes no AI-provider request.
- Uses deterministic intent/routing and an extractive research answer.
- Can search, fetch, read named sites, resolve downloads, retain evidence, and process follow-ups.
- Is not an LLM and must say so in user-facing language.

**Remote AI provider**

- The user configures Gemini, Groq, or an OpenAI-compatible endpoint and model.
- The model proposes one validated tool step at a time.
- Tool calls still pass through schema validation, approval policy, guards, timeouts, and output checks.
- Page content is fenced as untrusted input before it is returned to the planner.
- Provider-reported token usage feeds the per-run spend cap where available.
- Task context leaves the device; the UI must disclose that fact.

**Remote worker**

This is separate from a remote AI provider. `RemoteWorker` is intended to move the whole task to a server using a signed installation identity. The client contract currently uses:

- `POST {baseUrl}/tasks` with `identity`, `nonce`, `timestamp`, `payload`, and Base64 ECDSA signature
- `GET {baseUrl}/tasks/{taskId}/stream` returning SSE frames with `token`, `done`, or `error`
- EC P-256 keys generated in Android Keystore
- A five-minute signature freshness window in the shared verification model

There is no server implementation here, no production remote-task selector, and no end-to-end deployment. Keep this path off by default.

## Privacy and security invariants

Changes must preserve these rules:

1. No analytics, advertising SDK, browser-owned telemetry, or silent startup request.
2. History and search suggestions remain off by default; blocking remains on by default.
3. The visible WebView request path continues to call the local filter before a matching request leaves the app.
4. App/native HTTP connections go through `NetworkGate`; do not add direct `openConnection()` call sites elsewhere.
5. A privacy route that promises fail-closed must not silently fall back to direct networking.
6. Third-party cookies, mixed content, and WebView file/content access remain disabled unless a narrowly reviewed feature requires otherwise.
7. The blocklist digest is verified before parsing. An invalid list disables blocking and records the reason; it does not silently run untrusted rules.
8. Tool selection never grants permission. Every selected tool still enters `ToolPipeline`.
9. A missing approval UI or timed-out approval parks/refuses the action; it never defaults to allow.
10. Provider keys and granted account-cookie values remain inside Keystore-backed encrypted preferences; plaintext credential persistence fails the privacy audit.
11. Credentials and page content must not be copied into memory summaries, logs, or model prompts outside their explicit task use.
12. Private/isolated-profile claims must be capability-detected from the installed System WebView.
13. Approval tool grants are process-session only and return to the configured mode after restart.
14. Restricted tools remain hard-off unless a separate security and product decision deliberately changes source code and tests.
15. The remote worker can see remotely executed task URLs and page content. Never claim otherwise.
16. Android backup remains disabled unless every persisted secret and identity is re-audited.

Run `python3 tools/privacy_audit.py .` whenever code changes network, dependencies, the manifest, WebView policy, or persistence.

## Development setup

### Required for a full Android build

- Linux, macOS, or Windows with Flutter 3.24.5 (use the CI version for reproducibility)
- JDK 17
- Android SDK accepted by that Flutter release
- Python 3
- Network access for Flutter/Gradle dependencies

### Full local verification

```bash
# Point these at protected signing material; never commit the values.
export MRNOBODY_KEYSTORE=/secure/path/release.p12
export MRNOBODY_STORE_PASSWORD='...'
export MRNOBODY_KEY_ALIAS='...'
export MRNOBODY_KEY_PASSWORD='...'

cd app
flutter pub get
flutter analyze
flutter test
flutter build apk --release --split-per-abi

cd ..
python3 tools/test_privacy_audit.py
python3 tools/privacy_audit.py .
python3 tools/filter_digest_check.py .
tools/jvm_test.sh
for apk in app/build/app/outputs/flutter-apk/*-release.apk; do
  python3 tools/apk_size_check.py "$apk" 45
done
```

After Flutter has prepared the Android project, the Gradle JVM suite can also be run with:

```bash
cd app/android
./gradlew :app:testDebugUnitTest --console=plain
```

`tools/jvm_test.sh` is the fallback when Flutter or a complete Android SDK is unavailable. It downloads test dependencies and an Android platform into `/tmp/mrnobody-jvm`, compiles production Java plus tests with `javac`, and runs the full JVM suite. It does not build an APK and proves nothing about device behavior.

### Build only

After setting the four `MRNOBODY_*` signing variables shown above:

```bash
cd app
flutter pub get
flutter build apk --release --split-per-abi
```

Outputs are `app-<abi>-release.apk` under
`app/build/app/outputs/flutter-apk/`. A release task without complete signing
configuration fails; it never falls back to the debug key. CI uses the public
`.github/ci-test-signing.p12` key so successive test builds can upgrade in
place, verifies every APK's v2 signature, and publishes explicitly named test
artifacts. That public key must never sign a production release.

## Test strategy

| Layer | Command / location | What it proves |
|---|---|---|
| Static analysis | `flutter analyze` | Strict Dart/Flutter analysis |
| Flutter widgets | `flutter test` | UI behavior and bridge-facing state |
| Golden tests | `app/test/goldens/` | Screen regression against committed images |
| Fast JVM suite | `tools/jvm_test.sh` | Java compilation and unit behavior without Gradle/device |
| Android Gradle tests | `:app:testDebugUnitTest` | Android module unit suite in the normal build toolchain |
| Auditor self-tests | `python3 tools/test_privacy_audit.py` | The privacy auditor detects planted violations |
| Privacy audit | `python3 tools/privacy_audit.py .` | Prohibited permissions/dependencies/network bypass patterns are absent |
| Filter digest | `python3 tools/filter_digest_check.py .` | Shipped/source filter lists and digest pin agree |
| APK gate | `tools/apk_size_check.py` | Artifact stays below the hard size ceiling |
| Android emulator | `.github/workflows/android-emulator.yml` | Installs and drives API 31/34 virtual phones, exercises Flutter/native/SQLite/WorkManager integration, and captures device evidence |
| Physical-device matrix | `ROADMAP.md` | OEM WebView, hardware, storage, route, notification and battery behavior an emulator cannot prove |

### Hosted Android emulator tests

`Android Emulator Smoke` runs outside this workspace on GitHub-hosted KVM runners. It builds the debug application and instrumentation APKs, boots Pixel 6 profiles on Android 12 (API 31) and Android 14 (API 34), drives shipping Flutter widgets with `integration_test`, and crosses Android lifecycle/deep-link boundaries with instrumentation and UIAutomator. The stable smoke path covers first launch, a settings write and shell rebuild, a deep-linked local task, WorkManager completion, pipeline expansion/collapse, and conversation restoration after backgrounding.

Every matrix leg uploads:

- Instrumentation HTML/XML reports
- Test result XML
- In-app and final emulator screenshots
- UI hierarchy
- Logcat
- Activity/process state
- JobScheduler state
- Notification state

The smoke task intentionally uses the local conversational route rather than a live search provider: cloud providers may block CI IP addresses, which would test their anti-bot policy rather than the application. Controlled network/WebView fixtures can be added to this workflow separately. Emulator success still does not prove OEM battery restrictions, Orbot, hardware Keystore behavior, real camera/microphone/GPS, manufacturer WebView differences or external Storage Access Framework providers.

When changing a bug-prone path, add a regression test that fails under the old behavior. For device-only APIs, keep decision logic in pure Java where possible and add a thin Android seam plus a hardware test case.

### Controlled redirect test on a device

Do not judge blocking from a third-party test page that has disappeared or changed ownership. The checked-in `tools/manual_web/redirect_test_server.py` fixture server controls the trigger and contains allowed same-site links, listed top-level ad and tracker URLs, exact betting destinations, a scripted main-page redirect, and a popup request.

With the Android device attached:

```bash
python3 tools/manual_web/redirect_test_server.py
adb reverse tcp:8765 tcp:8765
```

Open `http://127.0.0.1:8765/` in Mr Nobody. Both ordinary links must reload the fixture (a `target=_blank` link is intentionally kept in the current tab). Every listed ad/tracker/betting control must leave the fixture visible and show a non-error blocked notice. The popup control must not create another surface. This validates runtime WebView behavior only on the tested device/System WebView combination; record the result in `ROADMAP.md`.

## Common change workflows

### Updating the blocklist

1. Update `filters/bundled/blocklist.txt`.
2. Produce/update `app/android/app/src/main/assets/blocklist.txt` with the filter tooling.
3. Update the pinned SHA-256 in `FilterEngine` only after reviewing the generated list.
4. Run `python3 tools/filter_digest_check.py .`.
5. Run JVM filter tests and the privacy audit.

Never edit one blocklist copy without the other.

### Adding or changing a tool

1. Define a precise `ToolSpec`, parameters, output contract, timeout, and worst-case tier.
2. Narrow the tier per action only when the risk is genuinely lower.
3. Register the tool in `MrNobodyApp` only when the related feature is enabled.
4. Route or plan to it explicitly; registration alone does not make it reachable.
5. Verify the call enters `ToolPipeline` and cannot bypass approval or guards.
6. Add contract, policy, failure, timeout, and adversarial tests.
7. Check that outputs do not leak credentials. Oversized values must remain bounded and explicitly state that omitted content was not retained.

### Adding a bridge method

1. Implement the native case in `MainActivity`.
2. Add a typed wrapper in `NativeBridge`.
3. Keep business logic in Java, not in the wrapper.
4. Use structured maps/lists with stable keys.
5. Return explicit errors rather than silently substituting a success value.
6. Add native and Flutter tests where the boundary can regress.

### Changing persistence

- Increment the relevant SQLite schema version.
- Add forward migration for every existing version that can reach the new one.
- Preserve fail-safe defaults.
- Exercise upgrade paths, not only clean installs.
- Remember that `allowBackup=false`; persistence is installation-local.

### Changing privacy/network behavior

- Preserve the single-egress `NetworkGate` rule.
- Test unavailable-route behavior as well as success.
- Report capability and effective state separately.
- Update [ROADMAP.md](ROADMAP.md) when a device-only claim changes state.

## CI

`.github/workflows/flutter.yml` runs on pushes to `main`, pull requests, and manual dispatch. The gate order is:

1. Documentation-surface check (exactly `README.md` and `ROADMAP.md`)
2. Build-dependency policy (no unused Kotlin plugin)
3. Flutter dependency resolution
4. Strict static analysis
5. Privacy-auditor self-tests
6. Repository privacy audit, including encrypted credential owners
7. Fast JVM suite
8. Filter digest check
9. Flutter widget/golden tests
10. Stable public CI test-signing configuration
11. ABI-specific APK build
12. APK signature verification
13. Gradle Android unit tests
14. 45 MiB size gate on every ABI APK
15. Explicitly named CI test-artifact upload

`.github/workflows/android-emulator.yml` is the runtime companion gate. It runs for app changes on main and pull requests, and can be manually dispatched. Its API 31/34 jobs are independent so one platform failure does not hide the other; each uploads evidence even when instrumentation fails.

There is one size limit: 45 MiB per installable ABI artifact. The current
artifacts are 14.62–18.19 MiB.

## Commit and review expectations

- Keep behavior changes separate from generated golden updates where practical.
- State what was verified and what was not.
- Do not describe off-device tests as physical-device validation.
- Include a regression test for bug fixes.
- Treat privacy wording as code: a claim that exceeds effective behavior is a defect.
- Update only this guide and `ROADMAP.md`; do not add one-off planning/status/spec documents. Fold durable developer information here and delivery status into the roadmap.

Commit author for this repository:

```bash
git config user.name "rfarouq69"
git config user.email "rfarouq69@gmail.com"
```

## License

MIT. See [LICENSE](LICENSE). Bundled font license texts remain under `app/assets/fonts/` and must not be removed.
