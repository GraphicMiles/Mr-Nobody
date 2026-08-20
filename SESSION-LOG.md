# SESSION LOG — Mr Nobody

In-repo continuation of the workspace `DEFECT-LOG.md` that died with the old
sandbox. One section per batch, newest first. Format: what shipped, why
(device evidence), how it is proven, and what the owner should retest.

---

## 2026-08-20 — Batch: read-loop economics + image downloads + benchmark fixes

**Scope (owner-approved):** items A, C and D of the handoff. Embedded Tor (B)
deliberately deferred to its own batch. Baseline `a83b4af`, 786 JVM tests
green; this batch ends at 838 green.

### A. Read-loop economics — the owner's six rules

Core principle wired in: *cheapest sufficient action first; escalate only on
validated failure, never preemptively.*

1. **Evidence-sufficiency early exit** — new `EvidenceSufficiency` (planner):
   after each read, if ≥2 distinct (body-deduped) sources each contribute ≥2
   question-matching prose sentences (reusing `ExtractiveAnswer.pick`, post
   boilerplate/prose gates, cross-source sentence dedupe), the remaining read
   steps are skipped without being fetched. Device evidence: "whats the time"
   53s/5 pages, "how old is messi" >69s/6 pages, both user-stopped.
2. **Escalate only on validated failure** — `readBestEffort` now refuses to
   launch the headless browser when the read could not be recorded anyway
   (read cap hit, duplicate URL, evidence already sufficient) — that was the
   "browser after a successful 3.0s http.fetch" screenshot. Escalation still
   happens when http output fails `ReadableText.usable()` / the contract, or
   the host is a known challenge/SPA. Escalated page loads are capped at
   **8s** (`BROWSER_FETCH_TIMEOUT_MS`), passed to `browser.fetch`/`links`.
3. **ClockSkill** — time/date/day questions answer from the device clock,
   zero network, before classification or planning. Handles "in <city>" by
   IANA-zone resolution; unresolvable places fall through to research rather
   than guessing a timezone. Hooked in `DeterministicEngine.run` right after
   the direct-reply path.
4. **No retry on success-but-imperfect** — `FetchRetry.MAX_ATTEMPTS` 3 → 2
   (one retry, hard failures 429/502/503 only, as before). The pngtree
   "2.1s then 20.2s recovered" pattern is killed by rule 2's guards.
5. **TaskBudget** — new wall-clock ceiling (`agent/policy/TaskBudget`):
   90s research / 120s download, checked between steps on both the
   deterministic and autonomous paths and inside the download link harvest.
   On expiry the engine composes the answer from evidence in hand (snippets
   at worst) — never a spinner death.
6. **Cheap-success ranking** — `SiteMemory` gains a clamped ±3 per-host
   http-outcome score (recorded by `HttpTool` on every fetch: usable text vs
   failure/needsBrowser); new `CandidateRank.byCheapSuccess` stable-sorts the
   CHOOSE PAGES candidates by it in `planReads`.

### C. Agent image downloads (device: "download a png icon from pngtree" → 0 links, 92.6s)

- `DownloadLinkResolver.wantsImage/requestedImageExt/isImage/resolveImage`:
  image intent detection, extension preference (.png beats .jpg beats .zip),
  named host still wins.
- `BrowserTool` links action takes `images=true` → collects `img[src]` and
  first `srcset` URLs alongside anchors (new `LINKS_AND_IMAGES_SCRIPT`),
  declared in the tool contract.
- `resolveDownload` (engine): for image tasks, the preview images the read
  loop already harvested (`r.images`) join the candidates at zero cost;
  candidates that already resolve (and honour a named host) skip the
  per-page browser link grind entirely; the harvest respects the TaskBudget.

### D. In-app benchmarks

- **`input.route` ("one misclassified")** — probed all six fixtures against
  HEAD Java `IntentRouter`: all six route correctly. The device ❌ was from a
  pre-`a83b4af` APK. No code change; owner should re-run Benchmarks on the
  new APK.
- **`settings.defaults` (`history=true` ❌)** — the benchmark read the LIVE
  prefs file, so any device where the user toggled history on "failed its
  defaults". `Settings` gains a named-prefs-file constructor; Diagnostics now
  probes an empty `mrnobody_defaults_probe` file (cleared before and after)
  and reports both defaults and current values. Code defaults were already
  correct (history off, blocking on).

### Enablers

- `MrNobodyApp.activeProvider()` is null-safe before `onCreate` (returns
  `LocalProvider`) — also what lets the whole `DeterministicEngine.run` be
  exercised on the JVM harness with fake tools for the first time.
- `ExtractiveAnswer.normalise` package-visible for `EvidenceSufficiency`.

### Tests (786 → 838)

- `DeterministicEngineReadLoopTest` — full engine runs with fake tools:
  early exit at 2 sources, no escalation on usable-but-bland http, escalation
  + 8s cap on validated failure, budget-expiry-still-answers, cheap-host
  ranking, clock-question-touches-no-tool.
- `EvidenceSufficiencyTest`, `ClockSkillTest`, `TaskBudgetTest`,
  `CandidateRankTest`; extended `SiteMemoryTest`, `DownloadLinkResolverTest`;
  `FetchRetryTest` updated for MAX_ATTEMPTS=2.
- `ReadLoopWiringTest` — source-wiring pins for everything Android-touching
  (per the harness's throwing-stub rule).

### Owner retest list (new APK) — this batch

1. "whats the time" / "what time is it in london" → instant, zero network,
   correct zone.
2. "how old is messi" → seconds, not 69+; answer cites ~2 sources.
3. Watch the ⓘ log: no browser.fetch after a successful http read; escalated
   loads ~8s max; no task past 90s (research) / 120s (download) without an
   answer.
4. "download a png icon from pngtree" → resolves an actual .png.
5. Dev mode → Benchmarks: "Address bar → URL/search/task" and "Privacy
   defaults" both ✅ (the latter even with history toggled on).

### Leftovers carried forward (from handoff §5E)

A1 private-clear incl. app-relaunch sweep, slash commands, follow-up chips,
6.3/6.16 junk answers, 6.5/6.6 YouTube, 6.8/6.15 routing, debug-ⓘ log
contents (9 entries at 20:39). Embedded Tor (B) is the next batch.
