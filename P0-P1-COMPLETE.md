# P0 + P1 Complete — Mr Nobody Reliability & DevTools

## P0 — Intermittent Page Load Fixes (COMMITTED & PUSHED)

### Problem: Pages sometimes load, sometimes don't
Root cause cluster identified and fixed:

#### 1. Blocked navigation left loading=true forever
**File:** `MrNobodyWebView.java:490-560`
- `shouldInterceptRequest` returns 204 for blocked main frame, sets `blockedMainFrameUrl`
- `onPageStarted`, `doUpdateVisitedHistory`, `onPageFinished` early-returned on `sameUrl(url, blockedMainFrameUrl)` without clearing loading state
- **Fix:** Now sends `onNavigation` with `loading:false` + restored URL (`navigationSource()`), clears `SwipeRefreshLayout`, sends blocked counters. Prevents spinner stuck forever.

#### 2. Cleartext HTTP dialog deadlock
**File:** `MrNobodyWebView.java:1089-1140`
- Boolean `cleartextDialogShowing` blocked all future navigations if dialog cancelled or activity died
- No notification to Dart that navigation cancelled → tab stuck loading
- **Fix:** Added `pendingCleartextUrl` queue, always sends `loading:false` navigation on deny or activity null, uses single dialog guard, proper dismiss handling.

#### 3. Renderer crash black screen
**File:** `MrNobodyWebView.java:560-610`
- `onRenderProcessGone` destroyed WebView and released channel without notifying Dart
- **Fix:** Now sends `onNavigation loading:false` + `onError` with "Renderer crashed — tap Retry" before release, so UI shows retry instead of black.

#### 4. onReceivedError stuck
- Added `loading:false` navigation in error case

#### 5. Tab retention eviction
**File:** `TabWebViews.java`
- Increased `MAX_RETAINED` from 6 to 10
- Added `onTrimMemory` callback to aggressively evict on memory pressure (level 60+ keeps 3 tabs)
- Added logging for eviction reasons (visible in debug overlay)
- Hooked in `MrNobodyApp.onTrimMemory`

#### 6. Flutter side
**File:** `browser_tab.dart`
- Added `_loadingTimeout` 25s safety auto-reset if native never sends loading=false
- Fixed thumbnail timer leak: check `_disposed`, debounce 800ms, limit 150KB, cancel both timers
- Added consoleLogs + networkLogs ValueNotifiers

**File:** `native_webview_engine.dart`
- Fixed pending queue race: create new channel first, then clear old handler
- Deduplicate `loadUrl` in pending, retry on MissingPluginException
- Added RepaintBoundary around PlatformViewLink

**File:** `tab_manager.dart`
- `applySettingsToAll` now parallel `Future.wait` + 500ms debounce, not sequential await
- Added `applySettingsIfNeeded` selective

**File:** `main.dart`
- `_onAppStateChanged` now skips WebView apply on theme-only changes (previously 6 channel calls on every theme switch)

**File:** `home_screen.dart` + `debug_fab.dart` + `tabs_screen.dart`
- Home polling only when active, 5s not 3s, didUpdateWidget handling
- Debug overlay: 15s slow poll when closed, 5s fast when open
- Tabs grid: `addAutomaticKeepAlives:false`, `cacheWidth:240`, RepaintBoundary, icon cacheWidth 28

### Result
- No more stuck loading spinner
- No more black screen after renderer gone
- No more cleartext dialog blocking all navigations
- Faster tab switching (parallel settings, debounced, RepaintBoundary)
- Reduced memory churn (thumbnail debounce, larger retention)

---

## P1 — DevTools Feature (COMMITTED & PUSHED)

### Research Conclusion
**Yes, fully feasible on Android WebView:**
- Remote: `WebView.setWebContentsDebuggingEnabled(true)` + `chrome://inspect` (1 line) — already enabled in debug builds + when Terminal toggle ON
- In-app console: override `WebChromeClient.onConsoleMessage` → forward via MethodChannel → ring buffer 300 entries
- In-app Elements: `evaluateJavascript("document.documentElement.outerHTML")`
- Full overlay: bundle `eruda.min.js` (444KB asset) and inject via `evaluateJavascript` — privacy safe, no CDN leak
- Network: log in `shouldInterceptRequest` (method, URL, blocked flag)

### Implementation

**Java:**
- `consoleBuffer` (300) + `networkBuffer` (200) + `networkTimings`
- `onConsoleMessage` override stores + sends `onConsole` event
- MethodChannel methods: `evalJs`, `getConsole`, `clearConsole`, `getNetwork`, `clearNetwork`, `getHtml`, `getCookies`, `getLocalStorage`, `injectEruda`
- Bundled `eruda.min.js` asset, fallback to CDN if missing
- Network logging in `shouldInterceptRequest` including blocked category

**Dart:**
- `BrowserEngine` extended with devtools methods + `onConsole` callback
- `NativeWebViewEngine` implements all with `_invoke` + JSON unwrapping
- `BrowserTab` holds `consoleLogs` and `networkLogs` ValueNotifiers, methods `evalJs`, `getHtml`, `clearConsole`, `injectEruda`, etc.
- `DevToolsScreen` — 5 tabs:
  - **Console:** live logs with level colors (error red, warn amber), source:line, copy button, auto-scroll
  - **Network:** request list with BLOCKED badges, method, URL, mainFrame icon, copy, reversed chronological, auto-poll every 2s
  - **Elements:** Load HTML button, selectable outerHTML, tip for Eruda
  - **Storage:** Cookies + LocalStorage viewer with Load button
  - **Eval:** JS input 8 lines, Run + Inject Eruda buttons, result with copy, quick snippets
- `BrowserScreen` menu: added "Developer Tools" → pushes DevToolsScreen, "Inject Eruda (inspect)" → one-tap overlay
- `SettingsScreen`: added Developer toggle (uses terminal flag) + Help dialog explaining 3 layers

**How to use:**
1. Enable DevTools in Settings > Developer > Developer Tools ON
2. Open any site, tap ⋮ menu > Developer Tools → see console/network/DOM
3. Or ⋮ > Inject Eruda → floating button appears on page → tap for full Elements/Console/Network/Sources
4. For desktop: connect via USB, open Chrome desktop > chrome://inspect → inspect WebView

### Privacy
- Eruda bundled as asset, no network fetch
- DevTools only when Terminal/DevTools toggle ON (or debug build)
- Private tabs still log but clear on close
- Console buffer in memory only, never persisted

---

## Next — General Polish (Proposed P2)

Already partially done, remaining:
- Keep BrowserScreen in IndexedStack with Offstage instead of pushed route (biggest switch-time win)
- Add skeleton shimmer for tab grid
- Address bar: show host when not focused, full URL when focused (desktop pattern)
- Add offline banner + retry with exponential backoff
- Add haptic feedback on tab close/new
- Reduce APK size: eruda 444KB could be lazy-loaded or stripped in release

Tell me to continue with P2 polish, and I'll implement the IndexedStack browser refactor + skeleton loading.

## Commits Pushed
- `606c2b0` P0: fix intermittent page load reliability + performance polish + DevTools foundation
- `8262b56` P1: DevTools v2 + polish — network, storage, eval + performance

Repo: https://github.com/GraphicMiles/Mr-Nobody (moved from Mr-nobody)
