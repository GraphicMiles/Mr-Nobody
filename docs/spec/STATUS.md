# Where Mr Nobody stands — V1 / V2 audit

Audited at commit `94e8039` against `docs/spec/V1_SPEC.md`, `docs/spec/V2_SPEC.md`
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
| 12 | Downloads | **Partial** | `DownloadTool` exists but is **not registered** with the engine, and the visible browser has **no download listener** — see §3.2 |
| 13 | Sandboxed terminal, feature-flagged | **Partial** | `PolicyGate` (ALLOW/CONFIRM/DENY) + `TerminalTool` exist; the tool is **not registered**, and the new Settings toggle currently gates nothing |
| 14 | Persistent task model | Done | `agent/tasks/TaskStore.java` (SQLite), `agent/core/Task.java` |
| 15 | Basic background tasks | Done | `TaskWorker` + `WorkManagerTaskScheduler`, resumable after process death |
| 16 | History OFF by default | Done | `browser/core/Settings.java` |
| 17 | Local ad/tracker blocking | **REGRESSED — not enforced** | see §3.1 |
| 18 | Cookie/storage controls | Partial | third-party cookies claimed "Blocked" in the dashboard, but nothing enforces it on the Flutter WebView; `clearData` does work |
| 19 | No analytics / no ads SDK / no account | Done | no SDKs in `app/android/app/build.gradle`; only network I/O is user-initiated |
| 20 | Privacy regression tests | Partial | `tests/privacy`, `tools/privacy_audit.py` exist but are not in the Flutter CI job |
| 21 | APK size benchmark | Done | CI reports size; `tools/apk_size_check.py` |
| 22 | GitHub Actions build/release | Done | `.github/workflows/flutter.yml` (analyze → tests → APK) |
| 23 | UI parity with the approved prototype | Done | all 11 views built; `app/test/screens_golden_test.dart` gates drift |

**V1 is ~80% real.** The UI half is now complete and gated by tests. The gap is
not features — it is that **the product's headline promise is currently not
enforced on the screen the user actually browses on** (§3.1).

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
| Notifications | **Not started** | no `NotificationManager` anywhere — a background task finishes silently |
| Human confirmation gates | **Not started** | no review/approve UI |
| Downloads | Partial | see §3.2 |
| Local-first task data | Done | SQLite, on-device |
| AI provider abstraction | Done | `AiProvider` + Gemini/Groq/OpenAI-compatible/Local, UI wired |
| Advanced privacy controls | Partial | profiles + param stripping exist in `Settings`; not all enforced |
| Filter-list integrity | Partial | bundled list, versioned; no signature/rollback |
| Decentralized filter distribution | Not started | |
| Security regression tests | Not started | |

---

## 3. What the audit found

### 3.1 Ad/tracker blocking is not applied to the visible browser (critical)

The pre-Flutter Java UI intercepted every sub-resource request and asked the
filter engine:

```
android/app/src/main/java/com/mrnobody/browser/MainActivity.java:1854
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request)
```

The Flutter app has no equivalent. `webview_flutter` exposes navigation
callbacks but **not** `shouldInterceptRequest`, so in `app/`, `FilterEngine` is
only ever read for dashboard counters:

```
app/android/.../MainActivity.java:85   m.put("pageAds", MrNobodyApp.filters().getPageAdsBlocked());
```

Consequences:

- **No ads or trackers are blocked** on the browser the user sees.
- The Privacy dashboard therefore reports **0 / 0 forever** — it is honest
  about a number that is only zero because nothing is filtering.
- "No ads. No tracking." is, on the visible path, currently untrue.

Fix options, in order of preference:

1. **Host the WebView as a Java PlatformView** (`MrNobodyWebView`) with a
   `WebViewClient.shouldInterceptRequest` that calls the existing
   `FilterEngine`, plus `setDownloadListener` and the JS/cookie policy. Zero new
   dependencies, filtering stays in-process (no channel hop per request), and
   the Dart side keeps a thin controller. Restores items 12, 17 and 18 at once.
2. Swap `webview_flutter` for `flutter_inappwebview`, which exposes
   `shouldInterceptRequest` on Android. Faster, but adds a large dependency and
   moves per-request decisions across the platform boundary.
3. JS-injected blocking — rejected: it cannot stop a request that has already
   left the device.

### 3.2 Downloads are half-wired

`DownloadTool` is never registered with the engine
(`MrNobodyApp` registers only `BrowserTool`), and the visible WebView has no
`setDownloadListener`, so tapping a download link in the browser does nothing.
The Downloads screen reads the system DownloadManager correctly, so it will
show whatever *other* apps downloaded and nothing of ours.

### 3.3 Task progress is a stub

```java
// agent/core/Task.java
public int progress() {
    switch (status) { case COMPLETED: return 100; ... default: return 0; }
}
```

Every running task reports 0%. Home and Task detail render that faithfully, so
the UI shows a 0% bar for the whole run. Progress should be derived from the
plan step index (`currentStep` / total steps) once the planner has a plan.

### 3.4 Nothing notifies

V1 §13 and V2 §15 both end their background-work flow with "Notification".
There is no notification code at all, so a task that finishes while the app is
closed is invisible until the user reopens the app.

---

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

## 5. Recommended order of work

1. Restore request interception + downloads on the visible path (§3.1, §3.2) —
   this is the product promise, and it is the only "regression" class item here.
2. Notifications for finished/failed background tasks (§3.4).
3. Real progress from plan steps (§3.3).
4. Register `TerminalTool` behind the existing Settings toggle, with a CONFIRM
   dialog wired to `PolicyGate` (V1 §9).
5. Then V2: typed tool schemas → tool router → multi-step loop → schedules →
   confirmation gates. See `docs/spec/COWAGENT_LEVERAGE.md` for how much of that
   can be borrowed instead of invented.
