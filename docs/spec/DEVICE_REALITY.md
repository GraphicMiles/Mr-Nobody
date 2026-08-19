# Device reality — honesty log

This is not a feature list. It is what is true after the Phase 2 code
fixes, and what is still **unverified** because nothing here has run on a
phone.

## Fixed in code (JVM-tested, not device-tested)

| Path | What was wrong | What the code does now |
|---|---|---|
| `HeadlessWebViewEngine.extractText()` | Returned only `currentTitle` | Evaluates `document.body.innerText` on the loaded page |
| `loadAndExtract` | Extracted on the first `onPageFinished`, including `about:blank` | Ignores blank navigations, waits 400ms, extracts body text |
| Local research answer | Dumped the search listing and called it an answer | `ExtractiveAnswer` cites pages that were actually read, and says no model was used |
| Search HTTP failure | DDG `/html/` is challenged | Tries DDG Lite HTTP, then escalates to the headless WebView. Failure no longer looks like "nothing exists" |
| Download report | Said "downloaded" on enqueue | Waits for `DownloadEngine.COMPLETED`. Still-running transfers are reported as in progress |
| Download resolver | Required `.mp4`/`.mkv`/… | Accepts `/download`, `export=download`, signed CDN objects |
| Evidence cards | `attachImages` existed and was never called | Images from pages that read are attached, 2–3 previews fetched through `PageImage`, persisted on the task |
| Follow-up "open the second one" | Could re-search and pick an earlier hostname | Pointer follow-ups skip search and open the pointed artifact URL |

## Still unverified on a device

Do not mark any of these WORKING.

- APK build / install / size gate against a real artifact
- Live WebView: open, fetch, extract, click, type, select, scroll, wait, submit
- Search over real internet (DDG challenge, Lite, browser escalation)
- Multi-page research + follow-up on a phone
- Download pause / resume / notification against a real server
- Evidence cards rendering local preview files in Flutter
- WorkManager after backgrounding, process death, recurring tasks
- Normal / Private / Nobody, MULTI_PROFILE isolation, Orbot fail-closed, DNS via SOCKS

## Not started (and not claimed)

Phase 3 Privacy V2 completion, Phase 4 remote worker, Phase 5 credits,
Phase 6 capability expansion. The local provider is **not** an AI agent.
Remote LLM reasoning exists only when the user configures a key.

## How to prove the path

On a phone, with a remote provider **off**:

1. `find laptops under 500000` → extractive brief from pages read, 2–3 cards
2. `open the second one` → that page, not a new search
3. A named-site download whose link has no `.mkv` → wait until COMPLETED before "Downloaded"
4. Kill the app mid-task → WorkManager resumes or reconciles; do not assume it

Until those four are watched, the agent is compiled and unit-tested, not working.
