# Where Mr Nobody stands — V1 / V2 audit

Audited at commit `fbdb3e9` against `docs/spec/V1_SPEC.md`, `docs/spec/V2_SPEC.md`
and `docs/spec/ARCHITECTURE_EXPLAINED.md`. Every claim below points at code.

---

## 1. V1 — Definition of Done

| # | Item | State | Evidence |
|---|------|-------|----------|
| 1 | Native Android app | **Done, with a deviation** | `app/` is Flutter UI + Java core. V1 §2 says Java for production UI — see §4. |
| 2 | HTML/CSS/JS prototype | Done | `prototype/interactive-wireframe.html` |
| 3 | Unified address/instruction bar | Done | `app/lib/screens/home_screen.dart` |
| 4 | URL navigation | Done | `router/intent_router.dart`, `browser/browser_tab.dart` |
| 5 | Search | Done | DDG in the visible path; `agent/tools/SearchTool.java` + `util/DdgHtmlParser.java` parse results for the agent path |
| 6 | Visible WebView | Done | `screens/browser_screen.dart` (webview_flutter) |
| 7 | BrowserTool interface | Done | `agent/browser/BrowserEngine.java`, `agent/tools/BrowserTool.java` |
| 8 | One tested headless backend | Done | `agent/browser/HeadlessWebViewEngine.java` — open/back/forward/reload/click/type/scroll/wait/extract |
| 9 | Basic agent routing | Done | `agent/planner/IntentRouter.java` (+ Dart mirror), unit-tested |
| 10 | Search / fetch / extraction tools | Done | `SearchTool`, `HttpTool`, `util/HtmlText.java` |
| 11 | Basic browser actions | Done | `HeadlessWebViewEngine.click/type/scroll` |
| 12 | Downloads | Done | `MrNobodyWebView.setDownloadListener` → DownloadManager; `DownloadTool` registered for the agent path; Downloads screen reads them back |
| 13 | Sandboxed terminal, feature-flagged | Done (V1 scope) | `TerminalTool` is registered **only while the Settings switch is on** (`MrNobodyApp.applyTerminalSetting`); `PolicyGate` runs ALLOW commands, refuses DENY, and reports CONFIRM as needing the user — the approval UI is V2 §11 |
| 14 | Persistent task model | Done | `agent/tasks/TaskStore.java` (SQLite), `agent/core/Task.java` |
| 15 | Basic background tasks | Done | `TaskWorker` + `WorkManagerTaskScheduler`, resumable after process death |
| 16 | History OFF by default | Done | `browser/core/Settings.java` |
| 17 | Local ad/tracker blocking | Done | `webview/MrNobodyWebView.shouldInterceptRequest` → `FilterEngine`; per-page counters + `PrivacyReport` totals; live count next to the padlock |
| 18 | Cookie/storage controls | Done | `CookieManager.setAcceptThirdPartyCookies(webView, false)`, no file/content access, mixed content blocked, `clearData` for the rest |
| 19 | No analytics / no ads SDK / no account | Done | no SDKs in `app/android/app/build.gradle`; only network I/O is user-initiated |
| 20 | Privacy regression tests | Done (static) | `tools/privacy_audit.py` runs in CI on every push and now audits **every** Android tree it finds, not a hardcoded path — see §3.3; `tools/test_privacy_audit.py` gates the auditor itself. Behavioural filter tests are still to write |
| 21 | APK size benchmark | Done | CI reports size; `tools/apk_size_check.py` |
| 22 | GitHub Actions build/release | Done | `.github/workflows/flutter.yml` (analyze → tests → APK) |
| 23 | UI parity with the approved prototype | Done | all 11 views built; `app/test/screens_golden_test.dart` gates drift |

**V1 is essentially complete.** The UI half is gated by golden tests, and the
privacy promise is now enforced on the visible path rather than asserted. What
is left is behavioural test coverage of the filter engine and the confirmation
UI for terminal CONFIRM commands, which is V2 work.

---

## 2. V2 — Definition of Done

V2 is deliberately mostly unbuilt, but the interfaces exist so it can be built
without a rewrite (`AgentEngine`, `Tool`, `Worker`, `TaskScheduler`,
`AiProvider`, `PrivacyEngine`, `BrowserEngine`).

| Item | State | Note |
|------|-------|------|
| Agent-first architecture | Done | Agent Home is the hub; the browser is a tool path |
| Unified instruction bar | Done | |
| Multi-step planning | **Not started** | `DeterministicEngine` runs a fixed cascade: search → optional page fetch → optional AI synthesis |
| Tool router | **Not started** | tools are a `LinkedHashMap` keyed by name; no selection logic |
| Search / HTTP / headless browser tools | Done | |
| Browser session isolation | Not started | one shared headless engine, no task-scoped cookie jars |
| Terminal sandbox | Partial | policy gate only |
| Typed tool schemas | **Not started** | `ToolRequest` is a `Map<String,String>`; no JSON Schema, no validation |
| Tool permission policy | Partial | `PolicyGate` classifies terminal commands only; no UI to answer CONFIRM |
| Prompt-injection defenses | **Not started** | page text is concatenated into the prompt with no provenance boundary |
| Persistent tasks / background execution | Done | |
| Resumable tasks | Done | bounded retry in `TaskWorker` |
| Scheduled tasks / monitoring | **Not started** | no `Schedule` model |
| Notifications | Done | `browser/TaskNotifier.java` posts on COMPLETED/FAILED from `TaskWorker`, deep-links to Tasks; permission is requested when the user starts their first task |
| Human confirmation gates | **Not started** | no review/approve UI |
| Downloads | Done | see V1 item 12 |
| Local-first task data | Done | SQLite, on-device |
| AI provider abstraction | Done | `AiProvider` + Gemini/Groq/OpenAI-compatible/Local, UI wired |
| Advanced privacy controls | Partial | profiles + param stripping exist in `Settings`; not all enforced |
| Filter-list integrity | Partial | bundled list, versioned; no signature/rollback |
| Decentralized filter distribution | Not started | |
| Security regression tests | Not started | |

---

## 3. What the audit found — and what has been fixed since

### 3.1 Ad/tracker blocking was not applied to the visible browser — FIXED (`fbdb3e9`)

The pre-Flutter Java UI intercepted every sub-resource request and asked the
filter engine (`android/.../MainActivity.java:1854`). `webview_flutter` cannot
express `shouldInterceptRequest`, so after the migration nothing was refusing
ad or tracker requests, and the dashboard could only ever report 0/0.

The visible page is now our own WebView hosted as a platform view
(`app/android/.../webview/MrNobodyWebView.java`), which puts `FilterEngine`
back on the request path in-process. That one change also restored the download
listener, the third-party cookie refusal, the JavaScript switch and tracking
parameter stripping, and let `webview_flutter` be dropped from the app
entirely — there is no longer a component that could quietly bypass the filter.

### 3.2 Downloads were half-wired — FIXED (`fbdb3e9`)

`setDownloadListener` hands the download to Android's DownloadManager;
unsupported `blob:`/`data:` downloads say so instead of failing silently.
`DownloadTool` is now registered with the agent engine as well.

### 3.3 Task progress was a stub — FIXED

`Task.progress()` derives from the position of the persisted `currentStep` in
`Task.PLAN`, so a running task reports 25 / 50 / 75 and survives process death
without a schema migration.

### 3.4 Nothing notified — FIXED

`TaskNotifier` posts a notification when a background task completes or fails,
deep-linking into Tasks. `POST_NOTIFICATIONS` is requested the first time the
user starts a task, not at launch where it would have no context.

### 3.5 The privacy audit was auditing dead code — FIXED

The most serious finding of this pass, because it silently invalidated the
evidence for item 20 above.

`tools/privacy_audit.py` resolved its targets as `root/android/app/src/main`.
The repository carries **two** Android trees:

| Tree | Last touched | Status |
|------|--------------|--------|
| `android/` | `69f76e9` | legacy, not built by anything |
| `app/android/` | `33a663b` | **the tree CI builds and ships** |

Nothing references `android/` — not `flutter.yml`, not either `settings.gradle`.
The audit was reading the abandoned copy and printing `CLEAN` on every push
while the shipping app went unexamined.

This was verified rather than assumed. Planting
`addJavascriptInterface(this, "leak")` and `setAllowFileAccess(true)` into the
live `MrNobodyWebView.java` — a remote-code-execution bridge and filesystem
access in the actual browser view — still produced:

```
PRIVACY AUDIT — CLEAN
CI exit code = 0
```

The audit now discovers every directory containing
`src/main/AndroidManifest.xml`, scans `.kt` alongside `.java`, prunes
`build/`-style noise, labels each violation with its tree, and fails loudly if
it finds no manifest at all (a wrong path must not read as "clean"). The same
four planted violations — JS bridge, `READ_CONTACTS`, a Firebase dependency,
and an SSL bypass — now each exit 1.

`tools/test_privacy_audit.py` locks this in with 9 tests that plant violations
and require failure. They were confirmed to fail (3 failures) against the old
auditor and pass against the new one, and they run in CI *before* the audit
itself: the auditor is tested before it is trusted.

**Caveat:** this restores the audit's reach; it does not broaden its rules. It
remains a static grep for a known prohibited list. It cannot see behaviour, so
the filter-engine gap below is still the real hole.

### 3.6 Still open

- Behavioural tests for the filter engine on a real page (the static privacy
  audit runs in CI; request-level assertions do not exist yet).
- The duplicate `android/` tree is dead code that no build references. It
  should be deleted, but that is a separate commit — it is 152 files and would
  bury the audit fix. Until then the audit covers both trees.
- The CONFIRM half of the policy gate has no approval UI, so those commands are
  refused rather than queued (V2 §11).
- Per-tab cookie isolation: `CookieManager` is process-wide, so a private tab
  clears on close rather than being truly isolated (V2 §10).

## 4. The Java-vs-Flutter deviation

V1 §2 states production Android code is Java, and §18 states the HTML prototype
is not the production UI. The repo migrated the presentation layer to Flutter
(`41effe4`) while keeping the whole core in Java. The current split is:

- **Java** — agent core, tools, headless browser, filter engine, task store,
  scheduler, settings, downloads. All of V1 §3–§16 lives here.
- **Flutter** — screens only, talking to the core over one MethodChannel.

That is a defensible reading of "the agent is the product, the UI is the body",
and it keeps the architecture interfaces intact. But it is a deviation from a
written constraint, and it is what caused §3.1 — the Java `WebViewClient` that
enforced blocking was left behind with the old UI. **This needs an explicit
decision:** either ratify Flutter-for-UI in V1 §2 (and re-implement the
interception layer as a PlatformView), or plan the UI back to Java.

---

## 5. Remaining work

V1 items 1–4 of the previous plan are done. What is left:

1. Behavioural filter tests: load a page with known ad hosts against
   `FilterEngine` and assert the requests are refused (§3.5).
2. Then V2, in this order: typed tool schemas → tool router → multi-step loop →
   confirmation gates → schedules. See `docs/spec/COWAGENT_LEVERAGE.md` for how
   much of that can be borrowed instead of invented — including wiring a
   user-run CowAgent instance into the `RemoteWorker` slot that V2 §9 already
   reserves.

---

## 6. Downloads are the app's own (2026-08-18)

Downloads were handed to Android's `DownloadManager`. Four user-reported faults
all trace to that one decision, so it was replaced rather than patched:

| Report | Cause |
| --- | --- |
| "I chose a folder but it downloaded to `…/files/staging/`" | `DownloadManager` cannot write into a SAF tree, so the file was staged app-private and copied afterwards. The staging path *was* the design. |
| "It used the native Android download manager" | It did. |
| "No pause and resume, only stop and delete" | `DownloadManager` has no pause API. |
| "The file kept downloading after I deleted the app" | The transfer belonged to the system, not to Mr Nobody. |

The replacement is `browser/download/`:

- `DownloadEngine` — the transfer, on our own threads and our own socket, so a
  pause is an actual pause. Resume is an HTTP range request validated with
  `If-Range`, so a file that changed on the server restarts instead of being
  spliced together from two versions.
- `DownloadSink` — writes **straight into the destination**: a document in the
  folder the user granted, or a `MediaStore` entry in public Downloads. No
  staging, no second copy of a four-gigabyte film.
- `DownloadStore` — SQLite, because a paused download has to survive the
  process being swept away.
- `DownloadService` — a foreground service so a transfer survives the user
  switching away, and — the point of the exercise — dies with the app.
- `DownloadNotifications` — the app's own notification, with Pause / Resume /
  Cancel on it.
- `DownloadResume` — the correctness-critical decisions as pure functions
  (16 tests), because a wrong resume produces a valid byte count and a corrupt
  file.

`DownloadMoveWorker`, `DownloadCompleteReceiver` and the staging half of
`DownloadDestination` are deleted. The agent's `DownloadTool` goes through the
same engine, so an agent download lands in the same folder and obeys the same
controls — no private back door to the filesystem.

### 6.1 Tabs keep their page

Flutter destroys a platform view when its widget leaves the tree, so leaving the
browser and returning destroyed the tab's `WebView`: it came back as a black
surface with nothing loaded, and Reload was a no-op because there was no
document to reload. `TabWebViews` now retains one `WebView` per tab id (capped
at 6) and the platform view merely adopts it; the page is destroyed only when
Dart reports the tab closed (`releaseTab`).

### 6.2 Screens carry their own Material

`SettingsScreen` is used both inside the shell's `Scaffold` and pushed as a
route from the browser menu. Only the first supplies a `Material` ancestor, and
without one `MaterialApp` styles every `Text` with its debug fallback — the
yellow-green double underline the user saw all over Settings. Every screen now
wraps in `ScreenSurface`, and `screen_surface_test.dart` fails on the cause
rather than on a pixel.

### 6.3 Not verified on a device

Written and tested off-device only; the sandbox has no Android runtime:

- SAF writes, `MediaStore` pending publish, pause/resume against a real server.
- Notification actions and the foreground-service promotion.
- WebView retention across tab switches, and the thumbnail capture it feeds.
