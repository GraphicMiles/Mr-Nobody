# Mr Nobody — V1 Product & Engineering Specification

## 1. Product Definition

**Mr Nobody** is a tiny, native Android web agent built around privacy.

The user does not have to think in terms of traditional browser commands. One unified input accepts URLs, searches, questions and simple actions. Mr Nobody chooses the lightest safe tool to handle the request.

### Core promise

> **No ads. No tracking by Mr Nobody. No automatic browsing history. Tell Mr Nobody what you want from the web.**

V1 establishes the native app, privacy foundation, agent/tool architecture, headless-browser interface, task persistence, downloads and background execution without trying to become a fully autonomous computer-use system.

## 2. Non-Negotiable Constraints

- Native Android application.
- Java for production Android code.
- Rust only for components where benchmarks show a real advantage.
- Python is development/CI tooling only.
- HTML/CSS/JS is a disposable visual/UX prototype, not the production UI.
- GitHub Actions is the official build, test, size-gate and release pipeline.
- Target release APK: **20–45 MB where technically feasible**.
- Do not bundle a full Chromium/Firefox engine merely for convenience.
- No advertising SDK, analytics SDK or telemetry by default.
- No mandatory account.
- No mandatory backend for normal browsing.
- History OFF by default.
- No central proxy for normal web traffic.

Mr Nobody must not claim universal anonymity. Websites can still receive IP addresses, account activity, submitted information and browser/device characteristics.

## 3. Fundamental Architecture

Mr Nobody is **not** a traditional browser with a chatbot attached.

It is:

```text
                 MR NOBODY
                     |
                Agent Core
                     |
          +----------+----------+
          |          |          |
       Search       HTTP     Browser
          |          |          |
          |          |     Headless Engine
          |          |          |
          +----------+----------+
                     |
                  Internet
```

The agent is the decision-maker.

The browser, terminal, downloader and search system are tools.

## 4. Unified Input

The main screen uses one instruction/address field:

```text
┌──────────────────────────────┐
│ Ask Mr Nobody or enter URL…  │
└──────────────────────────────┘
```

Examples:

```text
youtube.com
```

```text
latest Arsenal result
```

```text
Find laptops under ₦500,000
```

```text
Open this website and download the PDF
```

No manual mode selection is required.

## 5. Intent Routing

The Agent Core determines whether the input is:

- URL/navigation
- search/question
- simple task
- browser-interaction task
- download task

A simple request should not require an LLM if deterministic routing can solve it.

## 6. Tool Selection

Use the lightest suitable tool:

```text
1. Local deterministic logic
2. Search
3. Direct HTTP
4. HTML/text extraction
5. Headless browser
6. Terminal
7. External AI provider, only when needed/allowed
```

Example:

> "Summarize this webpage."

Try HTTP/extraction first.

> "Click Products on this JavaScript website."

Use the headless browser.

> "Hash this downloaded file."

Use the terminal/native utility.

## 7. Visible Browser

The normal human-facing browser remains separate:

```text
Native Java UI
       |
Android System WebView
       |
Website
```

The WebView is used for ordinary visual browsing.

The headless browser is an agent tool.

This separation keeps the APK small and prevents the agent architecture from depending on one rendering engine.

## 8. Headless Browser Interface

Define an engine-independent interface:

```text
BrowserTool
├── open(url)
├── back()
├── forward()
├── reload()
├── click(target)
├── type(target, text)
├── select(target, value)
├── scroll(direction)
├── wait(condition)
├── extract(target)
├── screenshot()
├── download()
└── close()
```

Possible engines must be benchmarked for Android/ARM64 support, size, RAM, JavaScript, CSS, modern websites, forms, sessions and reliability.

Candidates can include lightweight Rust/browser projects, but no project should be adopted solely because its README claims low memory use.

## 9. Lightweight Terminal

The terminal is a **restricted local tool**, not an unrestricted Linux computer.

Good uses:

- inspect downloaded files
- calculate hashes
- unzip supported archives
- parse/transform text
- run approved utilities
- manipulate an app-owned workspace

Architecture:

```text
Agent
  |
TerminalTool
  |
Policy Gate
  |
Sandboxed workspace
  |
Approved operations
```

Commands are classified:

```text
ALLOW
CONFIRM
DENY
```

Do not give the agent unrestricted root/system access or access to other apps' private data.

Prefer Android APIs for file operations when they are sufficient. Only bundle a terminal runtime if its size and maintenance cost are justified by measured requirements.

## 10. Agent Core

```text
Input
  ↓
Intent Parser
  ↓
Task Builder
  ↓
Planner
  ↓
Tool Router
  ↓
Executor
  ↓
Verifier
  ↓
Result
```

V1 planning should be simple and deterministic where possible.

The AI layer is optional for basic browser functionality.

## 11. AI Provider Abstraction

Do not hard-code one provider.

```text
AiProvider
├── LocalProvider
├── UserConfiguredProvider
└── OptionalRemoteProvider
```

If an external AI provider is enabled:

- clearly disclose that data may leave the device
- minimize transmitted data
- never silently upload browsing history or unrelated local data

## 12. Persistent Tasks

```text
Task
├── id
├── userInstruction
├── type
├── status
├── currentStep
├── progress
├── createdAt
├── updatedAt
├── retryCount
├── permissions
├── result
└── artifacts
```

Statuses:

```text
QUEUED
RUNNING
WAITING
PAUSED
COMPLETED
FAILED
CANCELLED
```

State must survive process death.

## 13. Background Work

Do not keep a hidden browser alive forever.

```text
User creates task
      ↓
Task saved locally
      ↓
Android schedules work
      ↓
Worker wakes
      ↓
Agent executes
      ↓
Task state updated
      ↓
Worker exits
      ↓
Notification
```

Follow current Android background and foreground-service rules.

## 14. Downloads

Use Android download facilities where practical:

```text
Agent
 ↓
Find permitted download
 ↓
Validate
 ↓
Confirm if required
 ↓
Download
 ↓
Notification
```

Do not implement DRM bypass, paywall bypass or access-control circumvention.

## 15. Privacy

Normal browsing should remain direct:

```text
User
 ↓
Mr Nobody
 ↓
Local privacy/filter layer
 ↓
Website
```

Not:

```text
User
 ↓
Developer proxy
 ↓
Website
```

unless a future user-controlled/privacy-preserving relay explicitly requires it.

## 16. Privacy Filter Core

```text
FilterCore
├── ad rules
├── tracker rules
├── cookie/storage policy
└── request decision
```

Rust may be used later if benchmarks justify it.

## 17. V1 Screens

```text
S1 First Launch
        ↓
S2 Agent Home
        ├── URL → Visible Browser
        ├── Search → Result
        └── Task → Task Detail
        ↓
S3 Sessions / Tabs
S4 Privacy
S5 Tasks
S6 Settings
S7 Clear Data
S8 Downloads
```

The Agent Home is now the center of the product.

## 18. HTML Prototype Workflow

```text
HTML/CSS/JS
      ↓
UI/UX experiments
      ↓
Phone testing
      ↓
Approved design
      ↓
Native Java implementation
```

The prototype is not the production browser.

## 19. Suggested Repository

```text
mr-nobody/
├── android/
│   ├── app/
│   ├── agent/
│   ├── browser/
│   ├── privacy/
│   ├── tasks/
│   ├── downloads/
│   └── terminal/
├── native/
│   └── rust/
│       ├── filter-core/
│       └── optional-browser-core/
├── prototype/
├── tools/
│   └── python/
├── tests/
└── .github/workflows/
```

## 20. GitHub Actions

CI must:

1. Run Java tests.
2. Run Rust tests when present.
3. Run Python tooling.
4. Build debug APK.
5. Build release APK.
6. Run privacy regression tests.
7. Run agent/tool tests.
8. Measure APK size.
9. Produce release artifacts.

Report:

- APK size
- installed size where measured
- native library sizes
- dependency changes
- ABI
- basic startup/memory benchmarks

## 21. V1 Definition of Done

- [ ] Native Android Java app.
- [ ] HTML/CSS/JS prototype.
- [ ] Unified address/instruction bar.
- [ ] URL navigation.
- [ ] Search.
- [ ] Visible WebView.
- [ ] BrowserTool interface.
- [ ] One tested headless backend.
- [ ] Basic agent routing.
- [ ] Search/fetch/extraction tools.
- [ ] Basic browser actions.
- [ ] Downloads.
- [ ] Sandboxed terminal interface, preferably feature-flagged until proven necessary.
- [ ] Persistent task model.
- [ ] Basic background tasks.
- [ ] History OFF by default.
- [ ] Local ad/tracker blocking.
- [ ] Cookie/storage controls.
- [ ] No analytics.
- [ ] No advertising SDK.
- [ ] No mandatory account/backend.
- [ ] Privacy regression tests.
- [ ] APK size benchmark.
- [ ] GitHub Actions build/release.
- [ ] UI parity with approved prototype.

## 22. V1 Non-Goals

Do not build:

- unrestricted autonomous computer use
- unrestricted terminal
- automatic purchases
- automatic account creation
- silent sending of messages/emails
- credential harvesting
- DRM/paywall/CAPTCHA bypass
- VPN/Tor
- cryptocurrency/P2P browser network
- cloud sync
- extension marketplace
- giant custom Chromium fork
- giant bundled browser engine

## 23. V1 Guardrail

Every feature must answer:

> Does this make Mr Nobody better at privately and safely helping the user use the web?

If not, defer it.
