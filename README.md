# Mr Nobody — Developer Guide

Mr Nobody is an Android 12+ privacy browser with an on-device task agent. Flutter renders the application chrome; a native Java core owns browsing, filtering, downloads, task execution, persistence, privacy controls, and AI-provider integration.

This is the repository's developer document. Product status and the next delivery phase are tracked in [ROADMAP.md](ROADMAP.md).

> **Accuracy boundary:** this guide describes the current source tree. A passing unit test or CI build is not the same as successful operation on physical hardware. Device-validation gaps are called out in the roadmap.

## Current snapshot

Production-code audit baseline: `21a051f` on 2026-08-19.

- App version: `1.0.0+1`
- Android application ID: `com.mrnobody.browser`
- Minimum Android version: API 31 / Android 12
- UI: Flutter 3.24.5 in CI, Dart SDK constraint `^3.5.4`
- Native core: Java 11 bytecode, built with JDK 17 in CI
- Rendering engine: the installed Android System WebView; no browser engine is bundled
- Latest audited CI run: successful, including analysis, Flutter tests, JVM tests, Gradle tests, privacy checks, release APK build, and size gate
- Audited release APK: 48,370,906 bytes (46.13 MiB); below the 70 MiB hard gate but above the 45 MiB product target
- Local JVM audit: 675 tests passing
- Python privacy-auditor suite: 11 tests passing

The source contains substantial Android functionality, but the complete local-agent, WebView, background-work, download, and privacy-route flows have not yet been signed off on a physical device. Do not convert “implemented and tested off-device” into a runtime claim.

## Product boundaries

### What is implemented

- Visible browsing through a native platform-hosted System WebView
- Multiple retained tabs, private-tab support when the installed WebView exposes multi-profile APIs, bookmarks, deep links, and per-site controls
- On-device ad/tracker filtering in `shouldInterceptRequest`, URL tracking-parameter stripping, local block counters, and a pinned bundled-list digest
- History disabled by default, third-party cookies disabled, mixed content blocked, file/content access disabled, and backup disabled
- App-owned downloads with destination selection, persistence, pause/resume/cancel, HTTP range validation, foreground-service support, and notifications
- Persistent tasks backed by SQLite and Android WorkManager, including retries, cancellation, heartbeat/reconciliation, event logs, follow-ups, and recurring schedules
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
- Release builds still use debug signing.
- AI-provider API keys are stored in app-private `SharedPreferences`, not in a Keystore-backed encrypted store.
- Several WebView privacy capabilities depend on the installed WebView version and can legitimately be unavailable on a device.
- Oversized tool output is currently reduced to a preview with a synthetic `spill://` locator, but the full value is not persisted behind that locator. Do not claim it is retrievable until backing storage exists.
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

The roadmap records a current risk in steps 5–6: task context is thread-local while tool bodies execute on a shared executor, and guard/browser state is shared by the singleton engine. Treat concurrent and headless-agent browser execution as unverified until that is corrected and regression-tested.

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
10. Credentials and page content must not be copied into memory summaries, logs, or model prompts outside their explicit task use.
11. Private/isolated-profile claims must be capability-detected from the installed System WebView.
12. Restricted tools remain hard-off unless a separate security and product decision deliberately changes source code and tests.
13. The remote worker can see remotely executed task URLs and page content. Never claim otherwise.
14. Android backup remains disabled unless every persisted secret and identity is re-audited.

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
cd app
flutter pub get
flutter analyze --no-fatal-infos
flutter test
flutter build apk --release

cd ..
python3 tools/test_privacy_audit.py
python3 tools/privacy_audit.py .
python3 tools/filter_digest_check.py .
tools/jvm_test.sh
python3 tools/apk_size_check.py app/build/app/outputs/flutter-apk/app-release.apk 70
```

After Flutter has prepared the Android project, the Gradle JVM suite can also be run with:

```bash
cd app/android
./gradlew :app:testDebugUnitTest --console=plain
```

`tools/jvm_test.sh` is the fallback when Flutter or a complete Android SDK is unavailable. It downloads test dependencies and an Android platform into `/tmp/mrnobody-jvm`, compiles production Java plus tests with `javac`, and runs the full JVM suite. It does not build an APK and proves nothing about device behavior.

### Build only

```bash
cd app
flutter pub get
flutter build apk --release
```

Output:

```text
app/build/app/outputs/flutter-apk/app-release.apk
```

The current Gradle release configuration uses debug signing. A CI-generated APK is therefore a test artifact, not a production release candidate.

## Test strategy

| Layer | Command / location | What it proves |
|---|---|---|
| Static analysis | `flutter analyze --no-fatal-infos` | Dart/Flutter analysis |
| Flutter widgets | `flutter test` | UI behavior and bridge-facing state |
| Golden tests | `app/test/goldens/` | Screen regression against committed images |
| Fast JVM suite | `tools/jvm_test.sh` | Java compilation and unit behavior without Gradle/device |
| Android Gradle tests | `:app:testDebugUnitTest` | Android module unit suite in the normal build toolchain |
| Auditor self-tests | `python3 tools/test_privacy_audit.py` | The privacy auditor detects planted violations |
| Privacy audit | `python3 tools/privacy_audit.py .` | Prohibited permissions/dependencies/network bypass patterns are absent |
| Filter digest | `python3 tools/filter_digest_check.py .` | Shipped/source filter lists and digest pin agree |
| APK gate | `tools/apk_size_check.py` | Artifact stays below the hard size ceiling |
| Device matrix | next-phase work | Actual WebView, storage, network, notification, and background behavior |

When changing a bug-prone path, add a regression test that fails under the old behavior. For device-only APIs, keep decision logic in pure Java where possible and add a thin Android seam plus a hardware test case.

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
7. Check that outputs do not leak credentials. Until backed spill storage exists, large-output handling must not claim the full value is retrievable.

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
2. Flutter dependency resolution
3. Static analysis
4. Privacy-auditor self-tests
5. Repository privacy audit
6. Fast JVM suite
7. Filter digest check
8. Flutter widget/golden tests
9. Release APK build
10. Gradle Android unit tests
11. 70 MiB APK size gate
12. APK artifact upload

The 70 MiB gate is a regression cliff, not the product goal. The target remains at or below 45 MiB; the audited 46.13 MiB artifact requires size work in the next phase.

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
