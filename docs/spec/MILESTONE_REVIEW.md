# Milestone brief — review against the codebase

Reviewed at `fe905e8`. The brief's strategy is right: make the agent
trustworthy before making it capable. What follows is where it meets the code —
what already fits, what will break, what is under-specified, and what I would
reorder.

**Verdict:** adopt it, with one insertion (close a bypass), one reordering
(recovery before contract), three corrections (schema representation,
fail-closed semantics, device identity timing), and two deferrals (loop breaker,
remote stack).

---

## 1. The finding that changes the plan: nothing calls tools with untrusted input yet

```java
// agent/ai/AiProvider.java
void complete(String systemPrompt, String userMessage, CompletionCallback callback);
```

That is the whole provider surface. **No provider can emit a tool call.** Every
tool invocation in the app today comes from our own code with hard-coded
arguments:

```java
// planner/DeterministicEngine.java:74, :86
runTool(context, "search",  ToolRequest.of("search", "q", instruction));
runTool(context, "browser", ToolRequest.of("fetch", "url", namedUrl));
```

Consequences for the brief:

| Phase | Value today | Why |
|---|---|---|
| 1 · Tool contract | **Partial** | Parameter validation has nothing untrusted to validate; the canonical-output half pays off immediately. |
| 2 · Pipeline | **High** | Policy, timeouts, error normalisation and the durable call record are valuable regardless of caller. |
| 3 · Approval | **High** | Terminal/download/exec gating is real today. |
| 6 · Anchored actions | **None yet** | `click()` has no caller: the planner never clicks. |
| 8 · Loop breaker | **None yet** | The engine makes at most three calls and never repeats one. There is no loop to break. |

This does not invalidate the brief — the foundation is worth laying before the
planner, not after. But two things follow:

1. **Add a small Phase 3.5: a tool-calling seam.** Extend `AiProvider` with a
   tool-call-capable completion and give `DeterministicEngine` an optional
   single-step "model picks one tool" path. Without it we will build the entire
   safety apparatus against a caller that cannot misbehave, and only discover
   the contract mismatches when the planner lands.
2. **Defer Phase 8** until that loop exists. Keep the post-execute hook in the
   pipeline so it becomes a 30-line addition later; shipping the detector now
   means shipping dead code.

## 2. There is a bypass that would silently defeat the pipeline

```java
// browser/MainActivity.java:136 — the "search" channel method
SearchTool tool = new SearchTool();
ToolResult r = tool.execute(getApplicationContext(), ToolRequest.of("search", "q", query));
```

The Flutter side can invoke a tool directly, around the engine. Add a pipeline
and leave this, and we have a guard with a documented hole.

**Insert Phase 0 (half a day):** one execution path. Route that call through the
engine/pipeline, and make direct `Tool.execute()` unreachable from outside the
pipeline package (package-private `execute`, public `ToolPipeline.run`). Cheap
now; unpleasant to retrofit after three phases of code assume the old shape.

## 3. "JSON schema" as the internal representation is a size trap

The brief asks for a parameter JSON schema and a canonical output JSON schema.
Android ships `org.json` only. A real JSON-Schema validator (networknt et al.)
pulls Jackson — megabytes against a 20–45 MB budget, for validation of maybe
fifteen fields.

**Correction:** keep schemas as small Java objects — `ParamSpec` (name, type,
required, enum, bounds) and an equivalent for the output — and *project* them to
JSON Schema text only when handing a tool list to a provider. Same guarantee,
about 150 lines, no dependency, and the model-facing projection is exactly the
"explicit allowlist" discipline from dsh: `execute`, timeouts and presenters
cannot leak into a request because they are not part of the projection.

Related: canonical structured results mean the core needs a JSON value type.
`org.json.JSONObject` is an Android class, and our Java unit tests
(`app/android/app/src/test/...`) run on a plain JVM — using it in core will
break them unless we add the test-only `org.json:json` artifact. Small, but it
must be in the plan.

## 4. Fail-closed needs a third outcome, or background tasks lose their capabilities

The brief (from dsh) says an unanswerable confirmation must not proceed. Right.
But on a phone the *normal* case for a background task is that there is no UI at
all — WorkManager woke us, the screen is off. If every CONFIRM resolves to DENY,
background tasks quietly become less capable than foreground ones, and the user
sees failures for things they would have approved.

We already have the state for the better answer, unused:

```java
public enum Status { QUEUED, RUNNING, WAITING, VERIFYING, COMPLETED, FAILED, CANCELLED }
```

**Correction:** an unanswerable confirmation should *deny the call* (nothing
executes — still fail-closed) and *park the task* as `WAITING`, post a
notification, and resume from the approval. Fail-closed, not fail-forever. It
also gives `TaskNotifier` and the review sheet a second reason to exist and
implements V2 §11 properly.

## 5. Phase 4 collides with the schema and with every reader

Two concrete problems:

```java
// tasks/TaskStore.java
private static final int VERSION = 1;
@Override public void onUpgrade(SQLiteDatabase db, int old, int now) {
    // V1 first schema.        <-- empty
}
```

- **The migration path does not exist.** Bumping `VERSION` to add `task_events`
  runs an empty `onUpgrade`, so anyone with an existing install gets a database
  without the new table and a crash on first insert. The migration has to be
  written as part of this phase, not assumed.
- **"The event log is the source of truth" as written means rewriting every
  reader** — `recent()`, `get()`, `TaskWorker`, and the Flutter list/detail — to
  fold events. That is a large, risky change for guarantees we can get more
  cheaply.

**Correction:** events are the truth, and the existing `tasks` row is a
**materialised projection** written in the same transaction. Readers and the UI
stay as they are, replay/repair becomes possible, and we can move to pure
folding later if there is ever a reason. Also specify sequence allocation:
`seq` must be per-task, contiguous, allocated inside the write transaction —
there are two writers (the worker and a UI-thread cancel request), so
`MAX(seq)+1` outside a transaction will collide.

## 6. Cancellation is three problems, and one of them is a 90-second blocking call

The brief's flow (persist → observe at a safe boundary → `TASK_CANCELLED`) is
correct. The code says the boundaries are further apart than they look:

- `WorkManagerTaskScheduler.cancel()` exists and **nothing calls it**.
- `TaskWorker.doWork()` never checks for cancellation or `isStopped()`.
- `DeterministicEngine.run()` is straight-line, and its AI step blocks:

```java
latch.await(90, TimeUnit.SECONDS);   // planner/DeterministicEngine.java
```

A cancel arriving during a provider call is invisible for up to ninety seconds.
So Phase 5 must also define: where the safe boundaries are (between plan steps,
and inside the provider wait via a short poll or an abort signal), and add the
UI affordance — Task detail currently offers only "Run again" and "Copy result".

## 7. Phase 6 refers to an engine layout we no longer have

The brief says "keep `BrowserTool → BrowserEngine → V1 WebViewBrowserEngine`".
Since `fbdb3e9` there are **two** stacks and `WebViewBrowserEngine` is gone:

| Path | Stack | Used by |
|---|---|---|
| Human | Dart `BrowserEngine` → `NativeWebViewEngine` → Java `MrNobodyWebView` (platform view) | the visible browser |
| Agent | Java `agent/browser/BrowserEngine` → `HeadlessWebViewEngine` | `BrowserTool` |

Anchored actions belong to the **agent** stack. Worth fixing in the brief so
nobody wires anchors into the browser the user drives.

Bigger point: an anchor needs something to anchor *to*. `HeadlessWebViewEngine`
exposes `extractText()` — a flat string with no element identities. Anchored
clicking implies a **typed page observation** (a list of candidate elements with
role, text, and a stable hash) that the planner reads and the click quotes
back. That is the real work in Phase 6, and it is a bigger piece than
"add a parameter to `click()`". It should be designed before it is scheduled.

## 8. Phase 7 spill is inert without a retrieval tool

"The agent can then request only the required portion" — with what? There is no
`read`, `slice` or `grep` tool. A locator the agent cannot dereference is a
dropped result with extra steps. Either add a minimal
`workspace_read(locator, offset, length)` in the same phase, or defer Phase 7
until the planner can do multi-step retrieval.

Note also that the current path truncates and discards (2 000 / 4 000 chars in
`DeterministicEngine`), so spill's first beneficiary is the *result the user
sees*, not the model.

## 9. Device identity: right instinct, wrong moment

Agreed on all the negatives — no IP identity, no account, no email, private key
in Keystore, public key as the anonymous identity. Three corrections:

1. **Generate the keypair lazily, when the user first enables remote
   execution** — not at install. A key minted at install is a permanent unique
   identifier sitting on disk for a feature most users will never turn on, and
   "no identifiers by default" is a claim we currently keep. `privacy_audit.py`
   should be taught to fail if key generation appears outside that path.
2. **One keypair per configured worker**, not one global device identity.
   A single key handed to two coordinators lets them correlate the same device.
3. **Rotation and deletion**: a "forget this worker" action that destroys the
   key, wired into Clear data. And explicitly *no* hardware attestation — it
   identifies the device, which is the thing we are avoiding.

`docs/THREAT_MODEL.md` and `docs/PRIVACY.md` need updating in the same change,
not afterwards.

## 10. The verification loop in the brief does not currently run

The brief says "after each major stage: compile, run tests, verify no
regression". For anything Java, that is not true today: CI runs
`flutter analyze`, the privacy audit, `flutter test` and `flutter build apk`.
The JVM unit tests under `app/android/app/src/test/` — where the pipeline,
policy and event-store tests would live — **are never executed**.

Prerequisite for the whole plan: add `./gradlew :app:testDebugUnitTest` to the
workflow. Three lines, and without it every Java test we write in these phases
is decoration.

## 11. UI work the brief under-scopes

Phase 3 is mostly Flutter, not Java:

- Settings → Agent → **Permissions** (mode picker replacing today's Terminal
  on/off, which becomes one tool's override).
- A **confirmation sheet**: tool, tier, arguments preview, the reason and the
  source of the decision, Approve / Deny / Always allow.
- A **WAITING** presentation in Home, Tasks and Task detail (§4 above).
- A **Cancel** affordance in Task detail.
- Goldens for each, in the existing monochrome kit.

Call it two days of UI. It is the part that makes the rest visible, so it should
not be discovered late.

On the mode list itself: `YOLO` should not ship under that name in a
privacy-first product, and arguably should not ship at all. `ALWAYS_ASK /
ALLOW_READS / ALLOW_WRITES` covers the useful ground; anything beyond that wants
a typed confirmation, not a preference.

---

## 12. Suggested order

The brief's order front-loads architecture whose payoff needs a planner, and
leaves a user-visible bug (tasks stuck `RUNNING`, no cancel) until stage five.
Amended:

| # | Stage | Why here | Rough |
|---|-------|----------|-------|
| 0 | Close the bypass, one execution path | Cheap now, unpleasant later | 0.5 d |
| 1 | Heartbeat, reconciliation, durable cancel (existing schema) | Fixes a bug users hit today; needs no model, no new tables | 1 d |
| 2 | Tool contract (tiers, param specs, canonical output) | Foundation; output half pays off immediately | 1.5 d |
| 3 | ToolPipeline (record → validate → policy → guards → timeout → normalise → persist) | The centrepiece | 1.5 d |
| 4 | Approval policy + Flutter review UI + WAITING flow | Makes the Terminal switch and V2 gates real | 2 d |
| 5 | Tool-calling seam in `AiProvider` + single-step loop | Gives the pipeline a caller that can misbehave | 1 d |
| 6 | Task event log (events truth, row projection) + migration | Payloads are stable once results are canonical | 1.5 d |
| 7 | Typed page observation, then anchored actions | Design first; largest single piece | 3 d+ |
| 8 | Spill + `workspace_read` | Only useful together | 1.5 d |
| 9 | Loop breaker | Needs the loop from stage 5 to exist | 0.5 d |
| 10 | Device identity (lazy, per-worker) + threat-model update | No coordinator to talk to yet | 1 d |
| 11 | SecureChannel + RemoteWorker | Last; optional by design | 3 d+ |

Two swaps versus the brief: recovery/cancellation moves to the front (it is the
only stage that fixes something a user can hit today), and the event log moves
after the tool contract so we do not define event payloads twice.

## 13. What I would cut

- **`YOLO` mode** — see §11.
- **Per-tool override UI** in the first pass; ship mode-only, keep the override
  in the resolver so power users get it via settings later.
- **Phase 8** until stage 5 exists.
- **Phases 9–11** until there is a coordinator to talk to. Designing the
  identity model now is cheap and worth doing on paper; shipping keys before
  there is anything to authenticate to is not.

## 14. What the brief gets exactly right

- Trustworthy before capable. That is the correct call for where we are.
- Canonical results with model/UI projections — the durable fix for the class of
  bug behind `5bbb925`.
- Monotonic guards. Adding a policy must only ever narrow.
- Events as the record, with state reconstructable from them.
- No account, no IP identity, remote strictly optional.
- Surveying without vendoring, and keeping V1 local-first.
