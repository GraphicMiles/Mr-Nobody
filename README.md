# Mr Nobody

Mr Nobody is an Android privacy browser with a built-in task agent.

In plain terms, it is a browser that can also do bounded jobs for you: search the web, read a few sources, explain what it found, download a file, or check something again later. The browser and the agent share the same privacy controls, download system, and network routing.

Mr Nobody is open source under the MIT License.

## What the app does

### Private browsing

- Uses the Android System WebView already installed on the device.
- Blocks known advertising and tracking requests locally.
- Blocks cross-site redirects into known gambling, pop-up/ad-network and ad-heavy link-shortener destinations.
- Strips common tracking parameters from links.
- Disables browsing history by default.
- Disables third-party cookies and mixed content.
- Supports normal and private tabs.
- Can route traffic through a configured proxy, Orbot, or the bundled Tor service.
- Fails closed when a privacy route that was promised is unavailable.
- Loads a plain `http://` page only after an explicit "Load anyway / Cancel" warning; the site host is remembered for the session. A download from a `http://` file also shows an insecure-connection warning with Continue / Reject before anything is fetched, and only sends the source page's Referer header over cleartext when the user approves that download. The agent's autonomous tools and AI providers still require HTTPS, so cleartext is never used outside the user's explicit browsing or download choice.
- A download URL that is a landing page (`…/film.mkv.html`) is resolved, not fetched as a file: the agent reads the page, finds the real file link, and downloads that, so it never saves an HTML page for a video. A genuinely direct file URL is still downloaded directly.

### Agent tasks

The agent can:

- Search the web and return parsed results.
- Read source pages and produce a cited answer.
- Use rendered WebView extraction when a plain HTTP read is insufficient.
- Download files through the app-owned download engine.
- Continue a conversation inside the same task.
- Run recurring checks with Android WorkManager.
- Pause for approval before consequential actions.
- Resume safely after process death without repeating committed effects.

The local agent is deterministic and does not contain an on-device language model. It searches, extracts, ranks, and verifies information with normal code. Do not describe it as a local LLM.

The extractive answer is ranked, not dumped: the engine classifies what the question wants (a figure, a person/identity, a definition, an explanation, a comparison), scores each candidate sentence on how well it answers *that*, rejects keyword/metadata dumps and site-menu rails, drops verbatim repeats, and renders a structured answer — a lead sentence with the key figure bolded and a short key-facts list. This keeps a price question answered with the price and a "who is" question answered with the identity, instead of whatever sentence merely mentions the topic.

Transport noise — HTML tags, undecoded entities, markdown links, JSON schema metadata and page furniture — is stripped before a sentence can be cited, so the model never reads (and the answer never quotes) the raw markup a page actually contained.

A Tier-0 fast path runs before any AI request, for both local and remote runs: a device-clock question, a simple arithmetic question ("what is 25% of 800"), and an instruction that names a single direct action (a file to download, a terminal command) are answered locally by the deterministic router — no AI provider call, and no page content leaves the device. A remote AI request is only made once the instruction has cleared that fast path — i.e. it needs classification, planning, or answer synthesis.

Users may optionally configure Gemini, Groq, or an OpenAI-compatible remote AI provider. When a remote provider is enabled, task context sent to that provider leaves the device. Tool execution still passes through Mr Nobody's local policy and execution ledger.

Each run records its phase breakdown (classify, plan, tool, synthesis, verify) as durations only into the debug log, for latency profiling; no task content is measured.

### Downloads

Downloads are owned by the app rather than handed to Android's system DownloadManager.

That allows the app to provide:

- A user-selected Storage Access Framework folder.
- Public Downloads as the default destination.
- Pause, resume, retry, and cancellation.
- Validated HTTP range resumption.
- Foreground progress notifications.
- Recovery after process death.
- Consistent behavior for browser-initiated and agent-initiated downloads.

### Interface polish

- Classic dark and Warm cream themes repaint the complete shell immediately.
- Theme colours transition smoothly across the bottom navigation, cards, controls, and home surfaces.
- The home mark uses ten native Flutter motion sequences in a shuffle bag: every movement plays once before reshuffling, never repeats twice in succession, and returns to the exact resting pose.
- Motion pauses while Home is hidden and is disabled when Android requests reduced motion.

## Privacy modes

| Mode | Meaning |
|---|---|
| **Normal** | Standard browsing through the current network connection. |
| **Private** | Reduced local retention, with stronger isolation when the installed WebView supports it. |
| **Nobody** | Uses the configured privacy route, preferring Orbot when available and otherwise using bundled Tor. It fails closed rather than silently falling back to a direct connection. |

Privacy is not the same as anonymity. Websites, accounts, remote AI providers, device configuration, and user behavior can still identify a person. The UI must report effective protection rather than claiming more than the device can provide.

## Current status

The main branch is green in GitHub Actions.

Current automated coverage:

- 1,028 Java/JVM tests
- 174 Flutter widget and screen-golden tests
- 14 privacy-auditor tests
- Strict Flutter analysis
- Android Gradle unit tests
- Filter integrity checks
- Signed ABI-specific APK builds
- APK signature and 45 MiB size gates

Important runtime paths still require physical-device verification. A successful unit test or emulator run does not prove Android Keystore behavior, OEM battery restrictions, System WebView capabilities, Tor routing, Storage Access Framework providers, or process-death behavior on every phone.

See [ROADMAP.md](ROADMAP.md) for current priorities and acceptance work. Historical implementation notes are kept in `SESSION-LOG.txt`.

## What is not finished

### Remote worker server

The repository contains a remote-worker client protocol, signed device identity, durable local task state, idempotency support, background-job abstractions, and reconnectable result concepts.

It does **not** contain the production remote-worker server. New user tasks still run locally by default. A server-side queue, worker isolation, persistence, cancellation, retention policy, and durable result service remain future work.

### Credits and payments

Credits, purchases, refunds, account recovery, and a remote billing ledger are not implemented. Do not sell credits until remote execution and recovery are reliable.

### Design-platform integrations

The codebase contains an experimental platform-neutral design foundation and a disabled Canva MCP client. Live platform integration work is suspended. It remains off unless a future release deliberately supplies approved client metadata, user authorization, device testing, and product approval.

Figma and Adobe Express adapters are not implemented.

## How the app is built

Flutter renders the app interface. Native Java owns security-sensitive and long-lived behavior.

```text
Flutter screens and navigation
            |
            | MethodChannel: mrnobody/core
            v
      MainActivity
            |
            v
      MrNobodyApp
       |    |    |
       |    |    +-- task store, event log, execution ledger, scheduler
       |    +------- agent engine, policy, tools, providers
       +------------ browser, privacy, downloads, Tor, settings
```

### Main components

| Area | Location | Responsibility |
|---|---|---|
| Flutter application | `app/lib/` | Screens, navigation, task chat, settings, and native bridge wrappers |
| Android host | `app/android/app/src/main/java/com/mrnobody/browser/` | MethodChannel, application bootstrap, WebView host, notifications, downloads, and privacy routing |
| Agent core | `app/android/app/src/main/java/com/mrnobody/agent/` | Planning, tools, policy, providers, task execution, skills, and recovery |
| Task persistence | `agent/tasks/TaskStore.java` | Durable task rows, run identity, deduplication, cancellation, schedules, and migrations |
| Execution ledger | `agent/execution/SqliteExecutionLedger.java` | Step/effect identity, idempotency, replay results, external references, and cost fields |
| Tool policy | `agent/core/ToolPipeline.java` | Validation, approval, guards, retries, timeout, execution, result validation, and recording |
| Background jobs | `agent/jobs/` | Persisted submit/poll/reconcile jobs and WorkManager polling |
| Browser filtering | `browser/blocking/` | Request blocking and tracking-parameter removal |
| Network boundary | `browser/net/NetworkGate.java` | The single native HTTP egress point and fail-closed route enforcement |
| Download engine | `browser/download/` | Transfer lifecycle, persistence, file destinations, and notifications |

## How a task runs

1. Flutter submits an instruction through the native bridge.
2. `TaskStore` atomically creates or deduplicates the task.
3. WorkManager schedules the task.
4. `TaskDispatcher` selects the local or remote worker.
5. The local worker binds a durable run context and one of two execution lanes.
6. The engine selects a top-level skill and a scoped set of tools.
7. Every tool call enters `ToolPipeline`.
8. The execution ledger replays committed results and blocks unsafe duplicate effects.
9. Task state and bounded events are persisted for the UI.
10. The final answer or failure is written to the task row and surfaced by notification when appropriate.

Task IDs may be reused for follow-ups and recurring checks, so external-effect identity includes:

```text
task_id + run_id + logical_step_id + effect_slot + operation_fingerprint
```

A completed call is replayed from the ledger rather than executed again. An ambiguous non-idempotent action stops with an explicit unknown outcome.

## Safety model

### Tool scope

A model or router selecting a tool does not grant permission.

- Research tasks receive read-oriented tools.
- Downloads are added only for download intent.
- The terminal is never part of ordinary research scope.
- Direct routed actions receive only the selected tool.
- Every selected call still passes through `ToolPipeline`.

### Approval

The app separates low-risk reads from consequential actions.

- Reads normally continue without interruption.
- Sandboxed file writes follow the configured approval mode.
- Live-page changes, commands, sends, and publishing can require approval.
- Deletion and payment always require confirmation.
- If no foreground user can answer, the action does not run.
- Session-level approval grants disappear after restart.

### Prompt and page safety

- Page content is treated as untrusted input.
- Remote-provider prompts fence source content from instructions.
- Oversized tool output is reduced to an explicit preview.
- Provider keys, account cookies, OAuth values, typed form data, and raw prompts are not copied into the task event log.
- Private and reserved network targets are blocked for autonomous tools.

## Local AI, remote AI, and remote workers

These are different concepts.

### Local agent

- Runs on the Android device.
- Uses deterministic planning and extraction. It classifies a question's shape (a figure, an identity, a definition, an explanation, a comparison) and ranks answer sentences against that shape, so it is not a raw keyword dump — but it is still a light-duty, rule-driven path with no language model.
- Best suited to **light, well-scoped tasks**: device clock/date, simple arithmetic, a direct download, a fact lookup with sources. It is not a general-purpose reasoner.
- Makes no AI-provider request.
- May still use the network for search, reading, downloads, or a user-requested website.
- **Ambiguous, compound, or creative instructions are the job of the remote model / remote worker, not the local path.** When no provider is configured, the local path handles what it can and says so plainly rather than fabricating a reasoned answer.

### Remote AI provider

- Supplies optional language-model planning or answer synthesis.
- Receives the task context sent to it.
- Does not receive browser cookies, design-platform credentials, or arbitrary local secrets.
- Does not bypass local tool scope, approval, idempotency, or budgets.

### Remote worker

- Would move the whole task to a server.
- Would see the URLs and content required to execute that task.
- Is not a complete production path in this repository.

## Development setup

### Requirements

- Flutter 3.24.5
- Dart 3.5.4
- JDK 17
- Android SDK compatible with the pinned Flutter/Gradle toolchain
- Python 3
- Android 12 / API 31 or newer for the application

### Clone and test

```bash
git clone https://github.com/GraphicMiles/Mr-Nobody.git
cd Mr-Nobody

cd app
flutter pub get
flutter analyze
flutter test

cd ..
tools/jvm_test.sh
python3 tools/test_privacy_audit.py
python3 tools/privacy_audit.py .
python3 tools/filter_digest_check.py .
```

`tools/jvm_test.sh` compiles and runs the native Java suite without building an APK. It is useful when a full Android SDK is unavailable, but it does not prove device behavior.

### Run in development

```bash
cd app
flutter run
```

### Build a release APK

Release signing fails closed. Set all signing values before running a release build:

```bash
export MRNOBODY_KEYSTORE='/secure/path/release.p12'
export MRNOBODY_STORE_PASSWORD='...'
export MRNOBODY_KEY_ALIAS='...'
export MRNOBODY_KEY_PASSWORD='...'

cd app
flutter pub get
flutter build apk --release --split-per-abi
```

Outputs are written under:

```text
app/build/app/outputs/flutter-apk/
```

CI uses a public test-only signing key so test APKs can upgrade in place. That key must never sign a production release.

## Device testing

Before calling a release device-verified, test at least:

- Fresh install, restart, and upgrade
- Settings persistence
- Provider-key save, restart, and removal
- Normal/private data separation
- History-disabled behavior
- Ad/tracker blocking with the controlled fixture
- Search, source reading, citations, and follow-ups
- Approval allow, deny, background, and session reset
- Download start, pause, resume, cancellation, and process death
- Recurring tasks and notification behavior
- Remote AI provider timeout and cancellation
- Nobody mode with bundled Tor
- Orbot priority and fail-closed behavior
- Offline, slow-network, rotation, and low-memory cases

Record the device model, Android version, System WebView version, ABI, network mode, and tested commit.

## Privacy and security rules for contributors

Changes must preserve these invariants:

1. No analytics, advertising SDK, or silent startup request.
2. History and search suggestions remain disabled by default.
3. WebView request filtering remains on the native request path.
4. Native HTTP traffic goes through `NetworkGate`.
5. A promised privacy route never silently falls back to direct networking.
6. Credentials remain in Keystore-backed encrypted storage.
7. Tool selection never bypasses scope or approval.
8. Missing approval fails closed.
9. Consequential retries reuse the same durable effect identity.
10. Task and event deletion also clears execution and async-job recovery state.
11. Android backup remains disabled unless all persisted identities and secrets are re-audited.
12. Privacy claims must describe effective behavior, not intended behavior.

When changing networking, dependencies, persistence, WebView policy, or the manifest, run:

```bash
python3 tools/privacy_audit.py .
```

## Repository rules

- Keep durable developer guidance in this README.
- Keep current priorities and acceptance status in `ROADMAP.md`.
- Keep implementation history in `SESSION-LOG.txt`.
- Do not add one-off Markdown status/specification files to the repository; CI intentionally permits only `README.md` and `ROADMAP.md`.
- Add a regression test for bug fixes.
- Do not describe emulator or JVM success as physical-device validation.
- Never commit API keys, access tokens, cookies, signing secrets, or authorization headers.

Commit author used by this repository:

```bash
git config user.name "GraphicMiles"
git config user.email "rfarouq69@gmail.com"
```

## License

Mr Nobody is licensed under the [MIT License](LICENSE).

Third-party components keep their own licenses and notices:

- Android System WebView is supplied by the device and is not redistributed as the app's own browser engine.
- Bundled Tor components are used under their applicable BSD-style licenses and are attributed in the app.
- Bundled font license files remain under `app/assets/fonts/`.
- "Tor" and the onion logo are trademarks of The Tor Project, Inc. The app must not imply affiliation or endorsement.
