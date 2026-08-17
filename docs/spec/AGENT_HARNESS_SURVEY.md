# Agent harness survey — oh-my-pi, DeepSeek Harness, DeerFlow, CowAgent

Four harnesses, read at their current HEAD, judged by one question: **which
problems have they solved that we also have, and what is the smallest shape of
their answer that fits a phone?**

All four are MIT. All four assume a desktop or a server, a frontier model over
the network, and a filesystem. Mr Nobody is a ~21 MB Android app whose agent
must work on-device, offline-first, with no mandatory backend and a
deterministic path that needs no LLM at all. So this is a survey of *designs to
port*, not code to vendor.

| Repo | What it is | What we take |
|------|-----------|--------------|
| **DeepSeek Harness** (`dsh`) | "Everything is a plugin" harness on Cordis, TS, 54 packages, developer preview | The **guarded tool-execution pipeline** and the **event-sourced session** — the most instructive of the four |
| **oh-my-pi** | Terminal coding agent, TS + ~80k lines of Rust, 31 tools, LSP/DAP | The **approval policy** (tier × mode × override) and **hash-anchored actions** |
| **DeerFlow** | ByteDance super-agent harness, Python + LangGraph, sandboxes, gateway | **Long-horizon run discipline**: event contract, leases, cancellation, artifact receipts |
| **CowAgent** | Personal assistant harness, Python, multi-channel | A **remote worker we can actually call** — see `COWAGENT_LEVERAGE.md` |

---

## 1. DeepSeek Harness — the tool pipeline

`dsh` is built on Cordis: plugins contribute services, typed events and
reversible effects to a shared context, and *every* part is a plugin — model
adapter, tool registry, session log, even the agent loop. It is in developer
preview and says so loudly. We are not adopting Cordis, and we are certainly
not adopting a 54-package TypeScript tree. We are taking its pipeline, because
it is the most carefully specified one in this survey and it answers questions
we are about to hit.

### 1.1 A tool call goes through phases, and each phase has one job

From `docs/tool-execution-pipeline.md`:

```
tool/call logged (durable, BEFORE execution)
  → tools/pre-execute waterfall     hooks, permission, sandbox → allow | deny | ask
  → approval prompt (one-shot)      absent or unanswerable ⇒ DENY
  → monotonic guards                deny or abstain — never allow
  → tools/execute waterfall         around-dispatch: timeout, retry, metrics
  → the tool body                   returns canonical JSON only
  → tools/post-execute              accept | block | replace | add context
  → normalization                   a throw becomes isError, never a crashed loop
  → finalizeContent → tool/result   one frozen, model-facing outcome
```

Four properties of that pipeline are worth more to us than the pipeline itself:

- **The call is logged before it runs.** If the tool crashes the device, the
  record that it was attempted survives. Our audit story needs this.
- **Approval fails closed.** "absent or unanswerable: deny" — on a phone the UI
  is frequently *not there* (backgrounded, screen off, task resumed by
  WorkManager). A confirmation that nobody can answer must deny, not hang and
  not proceed.
- **Guards are monotonic: deny or abstain, never allow.** Adding a policy can
  only narrow the permission surface. That is the property that keeps a
  security review tractable as the number of policies grows.
- **Errors normalize.** A throwing tool produces `isError`, not a dead agent.

Our current equivalent is `DeterministicEngine.runTool()`: a try/catch around
`tool.execute()`. Everything above is missing.

### 1.2 Tools declare a canonical output, and content is a projection of it

```ts
interface ToolOutputDefinition {
  schema: JsonSchemaNode                              // validated on every success
  render(args, value): ContentBlock[]                 // pure → what the model reads
  presentationMeta?(args, value): JsonValue           // pure → what the UI renders
}
```

The body returns **canonical JSON**; the model-facing text and the UI card are
*pure projections* of that validated value. And `schemas()` builds the
model-facing tool list through an **explicit allowlist** — `execute`,
`timeoutMs`, presenters and concurrency flags "must never leak into a model
request".

This is the strongest form of the model-vs-human split I flagged from CowAgent.
Instead of a tool hand-writing two strings, it returns one validated value and
the two audiences are derived. For us that is the durable fix for the class of
bug behind commit `5bbb925` (*"parse search results instead of dumping page
scrape"*): a tool physically cannot hand the model a page scrape if its output
schema says `{results: [{title, url, snippet}]}`.

### 1.3 Oversized output spills instead of entering the context

`packages/spill`: a tool result too large for the context is written to a
session-scoped private file and the model gets a **locator plus a retrieval
hint** ("use `read`/`grep` on this path") instead of the blob. The storage
details are worth copying verbatim in spirit: private `0700` root, unpredictable
name prefix, exclusive `open(..., 'wx', 0600)` so a planted symlink cannot
redirect the write, sanitized single path segment.

On a phone this stops being an optimization. A 3 MB page extract cannot enter a
small on-device context at all, and our `HtmlText` truncation currently just
throws the rest away. Spilling to app-private storage and handing the agent a
handle keeps the data available without paying for it in tokens — and the
security rules above are exactly the ones our download workspace needs (V2 §26).

### 1.4 The session is an append-only log; message history is derived

`docs/subsystems/session.md`: a `Session` is an append-only log of typed
events, lossless JSON, contiguous sequence numbers, and **the LLM message
history is derived from the log, never stored separately — replay is
re-derivation**. Extra event types are merge-extensible (compaction, hooks add
their own).

DeerFlow reaches the same conclusion from the durability side (§3.1). Two
independent harnesses converging on "the log is the truth" is a strong signal
for our task model, which currently stores a status string and a step name.

### 1.5 A loop breaker that costs nothing until it fires

`packages/guard/repeat-tool-reminder`: count consecutive calls with identical
canonicalized arguments, and at thresholds (3, 5, 8) inject an escalating
advisory telling the model to re-read the last result and change approach.
Three details make it good: **denied calls count** (a model hammering a denied
call is exactly the loop worth breaking), bookkeeping tools can be excluded so
they cannot launder a loop, and the reminder rides as *additional context* — it
never rewrites the tool result, so the audit record stays the tool's own output.

Cheap, and worth more with a small model than with a frontier one.

### 1.6 ACP — a standard vocabulary for talking to an agent

`packages/acp` implements the [Agent Client Protocol](https://agentclientprotocol.com)
over JSON-RPC: `session/new`, `session/prompt`, `session/update` (streamed
chunks), `session/cancel`, and — the interesting one —
**`session/request_permission`**, a one-shot allow/reject carrying a tool-call
id that the client may answer automatically by policy.

That is our confirmation gate as a wire message, already standardized. If we
build the remote-worker link (V2 §9), speaking ACP rather than a bespoke client
means any compliant agent can be the worker, and the permission round-trip
comes with it. Caveat: dsh's ACP transport is stdio JSON-RPC, so a phone still
needs a small network bridge — adopt the *vocabulary*, choose our own transport.

### Not for us

Cordis and the plugin tree, 54 packages, LSP/DAP, the web host. And it is a
developer preview with breaking changes promised — another reason to port ideas
rather than depend on it.

---

## 2. oh-my-pi — the approval policy

A coding agent with the IDE wired in: 60+ providers, 31 tools, LSP and DAP,
subagents, a real browser, hash-anchored edits. Two ideas matter to us.

### 2.1 Approval = tier × mode × override

Every tool declares a **capability tier** — `read` / `write` / `exec`. The
session runs in an **approval mode** — `always-ask` / `write` / `yolo` — and the
mode sets the highest tier that runs unprompted. The user can pin a per-tool
policy of `allow` / `deny` / `prompt`, and the resolver records *which* of tool,
user or mode decided, plus a reason to show in the prompt
(`packages/coding-agent/src/tools/approval.ts`).

Ours is a string-prefix match over shell commands
(`agent/policy/PolicyGate.java`): `"rm -rf"` → DENY, `"sha256 "` → ALLOW, the
rest → CONFIRM. It only understands the terminal, cannot classify a download or
a form submission, and has no user-facing setting. Combined with dsh's pipeline
(§1.1) this is the design:

```java
// agent/core/Tool.java (sketch)
public interface Tool {
    String name();
    String description();
    /** read = observes; write = changes local state; exec = side effects off-device. */
    Tier tier();
    /** JSON Schema for params — validated before the tool ever sees them. */
    String parameterSchema();
    /** JSON Schema for the canonical result — rendered for model and UI separately. */
    String outputSchema();
    ToolResult execute(Context context, ToolRequest request);
}

// policy/PolicyGate.java (sketch)
Decision decide(Tool tool, ToolRequest request) {
    // 1. per-tool user override, else
    // 2. tool tier vs the user's approval mode, else
    // 3. CONFIRM — and if nothing can answer a CONFIRM, DENY.
    // Always carries a source and a human-readable reason.
}
```

Then "Terminal: on/off" in Settings becomes the honest control it claims to be
— *Agent permissions: ask every time · allow reads · allow writes* — and a
CONFIRM has somewhere to go (a review sheet) instead of being refused, which is
what V1 ships today.

### 2.2 Hash-anchored edits → hash-anchored **page actions**

`hashline` binds every patch hunk to a content hash of the file it was planned
against. If the file moved under the agent, the anchor is stale and the patch is
refused or recovered — never applied to the wrong lines.

We do not edit files. We do something with the same failure mode: the agent
plans `click("Add to cart")` against a page it read a second ago, the DOM
changes, and the click lands on whatever is there now.
`HeadlessWebViewEngine.click(selector)` cannot tell the difference.

```java
boolean click(String selector, String anchorHash);  // hash of the element's
                                                    // text + attrs at plan time
```

The engine re-hashes the resolved element and refuses on mismatch, returning a
typed "page moved" result the planner can re-observe from. It converts the worst
class of automation bug — silently doing the wrong thing — into a clean,
recoverable failure (V2 §7, §25).

### 2.3 Also worth stealing

`ToolLoadMode: essential | discoverable` — rarely used tools are pulled out of
the top-level schema and reached through search, so their schemas are not on
every request. Context is scarcer on a phone than on a workstation.

---

## 3. DeerFlow — long-horizon run discipline

ByteDance's harness for tasks that run minutes to hours. Recommended deployment
is 8 vCPU / 16 GB with Postgres, Redis and sandbox containers — not embeddable.
Its value is that it has been bitten by every failure mode of a task that
outlives its process and wrote the answers down.

- **Versioned event-stream contract** (`contracts/run_event_stream_contract.json`):
  frozen event names, monotonic `seq` per thread, consumers must ignore unknown
  types and fields, additive-vs-breaking rules spelled out, replay from a
  cursor, and an explicit **`gap` event** when the retained buffer was trimmed
  rather than a silent partial replay.
- **Ownership leases, heartbeats, orphan recovery.** Our matching bug is
  concrete: kill the app mid-task and the row stays `RUNNING` forever, so Home
  shows a live task until the end of time. The fix is small — treat
  `updated_at` as a heartbeat and reconcile stale `RUNNING` rows on app start
  and on worker wake.
- **Cancellation is a persisted request**, observed by the owner at a step
  boundary; first accepted action wins. We have `Task.Status.CANCELLED` in the
  enum and *nothing that sets it* — no way to stop a task you regret.
- **Artifacts have a boundary and a receipt**: outputs live under a dedicated
  directory and a terminal delivery receipt is recorded. That is V2 §26's
  download workspace with the details filled in.
- **Durable remote tasks**: submit/status/cancel with persisted remote ids,
  polling with backoff and restart recovery — the shape for polling a remote
  worker from a phone that sleeps.

Not for us: LangGraph, the gateway topology, Redis stream bridges, container
sandboxes, multi-worker anything.

---

## 4. CowAgent — the remote worker

Covered in `COWAGENT_LEVERAGE.md`. A mature build of most of our V2 half (agent
loop with a step budget, JSON-Schema tools, scheduler, tiered memory,
sub-agents, MCP, steering/cancel) that already exposes an authenticated
HTTP + SSE API. The play is to wire a **user-run** instance into the
`RemoteWorker` slot V2 §9 reserves — their machine, not a developer proxy.
Given §1.6, prefer ACP as the vocabulary if we can, with CowAgent's HTTP API as
the fallback transport.

---

## 5. What we take, in order

| # | Take | From | Lands in | Spec |
|---|------|------|----------|------|
| 1 | Tool contract v2: JSON-Schema params validated before execute, canonical output schema, model/UI renders as pure projections, model-facing allowlist, cooperative cancel signal | dsh (+CowAgent) | `agent/core/Tool.java`, `ToolRequest`, `ToolResult` | V2 §7, §23, §28 |
| 2 | Guarded execution pipeline: log the call first, pre-execute policy, **fail-closed** approval, monotonic guards, timeout as a wrapper, throws → `isError` | dsh | `planner/DeterministicEngine`, new `agent/core/ToolPipeline` | V2 §23 |
| 3 | Approval = tier × mode × per-tool override, with source + reason; Settings row; review sheet for CONFIRM | oh-my-pi | `policy/PolicyGate.java`, Settings, new confirm UI | V1 §9, V2 §11 |
| 4 | Task event log: append-only, contiguous seq, status derived from events; a frozen contract (ignore-unknown, additive rules) | dsh + DeerFlow | new `agent/tasks/TaskEventStore`, Task detail | V2 §14 |
| 5 | Heartbeat + reconciliation of orphaned runs; cancellation as a persisted request | DeerFlow | `TaskStore`, `TaskWorker`, Tasks UI | V1 §12, V2 §14 |
| 6 | Spill oversized tool output to app-private storage, hand the agent a locator | dsh | `HtmlText`, `BrowserTool`, workspace | V2 §26 |
| 7 | Anchored page actions (refuse when the DOM moved) | oh-my-pi | `BrowserEngine`, `BrowserTool` | V2 §7, §25 |
| 8 | Repeat-call loop breaker (denied calls count; advisory, never rewrites the result) | dsh | `planner/` | V2 §5 |
| 9 | Tool router picks the lightest tool; rarely used tools stay out of the prompt | oh-my-pi | `planner/` | V1 §6, V2 §6 |
| 10 | Sub-agent isolation for untrusted page content | dsh / CowAgent | `planner/` | V2 §24 |
| 11 | Optional remote worker, ACP vocabulary where possible | dsh ACP / CowAgent | `dispatcher/RemoteWorker.java` | V2 §9 |
| 12 | Artifact boundary + delivery receipt for downloads | DeerFlow | `DownloadTool`, workspace | V2 §26 |

**Items 1–5 are the next unit of work.** They are enforcement, not features:
every later item (planning loop, confirmation gates, schedules, remote work)
assumes a tool call is validated data with a policy in front of it and a log
behind it.

## 6. What none of them solve for us

- **An agent that works with no model.** All four assume a frontier LLM is
  reachable. Our deterministic path is the floor and stays the default; the AI
  provider is opt-in and often "Local (on-device)".
- **Doze, background limits, battery.** WorkManager semantics are ours alone.
- **APK size.** Every idea above must arrive as Java inside a 20–45 MB budget,
  which rules out vendoring any of their runtimes.
- **On-device headless browsing.** Their browsers are desktop Chromium or
  Puppeteer; V1 §8 still needs its own measured decision.

## 7. Licensing

oh-my-pi (MIT, © Mario Zechner / can1357), DeepSeek Harness (MIT, © DeepSeek),
DeerFlow (MIT, © ByteDance), CowAgent (MIT, © zhayujie), Mr Nobody (MIT).
Porting a *design* carries no obligation; copying code does. Everything above is
design. If we ever paste a file, it goes in a `NOTICES` file with its copyright
line.
