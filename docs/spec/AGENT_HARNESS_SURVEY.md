# Agent harness survey — oh-my-pi, DeerFlow, CowAgent

Three harnesses, read at their current HEAD, judged by one question: **which
problems have they solved that we also have, and what is the smallest shape of
their answer that fits a phone?**

All three are MIT. All three assume a desktop or a server, a frontier model
over the network, and a filesystem. Mr Nobody is a ~21 MB Android app whose
agent must work on-device, offline-first, with no mandatory backend and a
deterministic path that needs no LLM at all. So this is a survey of *designs to
port*, not code to vendor.

Short version:

| Repo | What it is | What we take |
|------|-----------|--------------|
| **oh-my-pi** | Terminal coding agent, TS + ~80k lines of Rust, 31 tools, LSP/DAP | The **tool contract** and the **approval model** — the best-designed of the three |
| **DeerFlow** | ByteDance "super agent harness", Python + LangGraph, sandboxes, gateway | The **long-horizon run discipline**: event contract, leases, cancellation, artifact receipts |
| **CowAgent** | Personal assistant harness, Python, multi-channel | A **remote worker we can actually call** — see `COWAGENT_LEVERAGE.md` |

---

## 1. oh-my-pi — the tool harness

A coding agent with the IDE wired in: 60+ providers, 31 built-in tools, LSP and
DAP operations, subagents, a real browser, hash-anchored edits. Most of it is
irrelevant to us (we are not editing code). Four ideas are not.

### 1.1 Approval = tier × mode × override

Every tool declares a **capability tier** — `read` / `write` / `exec`. The
session runs in an **approval mode** — `always-ask` / `write` / `yolo` — and the
mode sets the highest tier that may run unprompted. On top, the user can pin a
per-tool policy of `allow` / `deny` / `prompt`, and the resolver records *which*
of tool, user or mode decided, plus a reason to show in the prompt
(`packages/coding-agent/src/tools/approval.ts`).

Ours is a string-prefix match over shell commands
(`agent/policy/PolicyGate.java`): `"rm -rf"` → DENY, `"sha256 "` → ALLOW,
everything else → CONFIRM. It only understands the terminal, cannot classify a
download or a form submission, and has no user-facing setting.

This is the single cleanest port available to us, and it is what V2 §11 (human
confirmation) and §23 (LLM output is data, not authority) actually need:

```java
// agent/core/Tool.java (sketch)
public interface Tool {
    String name();
    String description();
    /** read = observes; write = changes local state; exec = side effects off-device. */
    Tier tier();
    /** JSON Schema for params — validated before the tool ever sees them. */
    String parameterSchema();
    ToolResult execute(Context context, ToolRequest request);
}

// policy/PolicyGate.java (sketch)
Decision decide(Tool tool, ToolRequest request) {
    // 1. user override for this tool, else
    // 2. tool tier vs the user's approval mode, else
    // 3. CONFIRM
    // Always carries a `source` and a human-readable `reason`.
}
```

With that, "Terminal: on/off" in Settings becomes the honest control it claims
to be — *Agent permissions: ask every time · allow reads · allow writes* — and
a CONFIRM decision has somewhere to go (a review sheet) instead of being
refused, which is what V1 does today.

### 1.2 Tools the model can't see cost nothing

`ToolLoadMode` is `essential` or `discoverable`: discoverable tools are pulled
out of the top-level schema and reached through a device URL or BM25 search, so
their schemas are not on every request. On a phone this matters more than on a
workstation — a small local model has a tiny context, and every tool schema we
send is context we cannot spend on the page. Our tool list should be pinned per
task type, not "everything we own, every turn".

### 1.3 Tool results carry three audiences

`AgentToolResult` has `content` (what the model reads), `details` (what a UI
renders), `isError`, and `useless` — a flag saying "this result is safe to drop
during compaction" (zero matches, a wait that timed out). CowAgent has the same
model/human split (`result` vs `display`); oh-my-pi adds the compaction hint.

We learned the model/human lesson the hard way — commit `5bbb925`, *"parse
search results instead of dumping page scrape"*. Making it a field instead of a
convention is how it stops recurring.

### 1.4 Hash-anchored edits → hash-anchored **page actions**

`hashline` binds every patch hunk to a 4-hex content hash of the file it was
planned against. If the file moved under the agent, the anchor is stale and the
patch is *refused or recovered*, never applied to the wrong lines.

We do not edit files. We do something with the same failure mode: the agent
plans `click("Add to cart")` against a page it read a second ago, the DOM
changes, and the click lands on whatever is there now. Nothing in
`HeadlessWebViewEngine.click(selector)` can tell the difference.

Porting the principle is cheap and high-value for V2 §7/§25:

```java
// BrowserEngine
boolean click(String selector, String anchorHash);  // hash of the element's
                                                    // text+attrs at plan time
```

The engine re-hashes the resolved element and refuses on mismatch, returning a
typed "page moved" result the planner can re-observe from — which turns the
worst class of automation bug (silently doing the wrong thing) into a clean,
recoverable failure.

### Not for us

The 31-tool surface, LSP/DAP, desktop control, the Rust workspace. And note
oh-my-pi's browser tool is Puppeteer/CDP on a desktop — it says nothing about
our on-device engine question (V1 §8), which still needs its own benchmark.

---

## 2. DeerFlow — long-horizon run discipline

ByteDance's harness for tasks that run minutes to hours: sub-agents, memory,
sandboxes, skills, a message gateway. Recommended deployment is 8 vCPU / 16 GB
with Postgres, Redis and sandbox containers — this is emphatically not
something to embed. Its value to us is that it has already been bitten by every
failure mode of a task that outlives its process, and wrote the answers down.

### 2.1 A versioned event-stream contract

`contracts/run_event_stream_contract.json` freezes event names and categories
and states the rules: events carry `thread_id`, `run_id`, a **monotonic `seq`
per thread**, type, category, content, metadata, timestamp; consumers **must
ignore unknown event types and fields**; adding an event type or optional field
is additive, renaming or removing is breaking. Reconnecting consumers replay
from a cursor, and when the retained buffer has been trimmed the stream emits
an explicit **`gap` event** rather than silently returning a partial replay.

We have nothing like this. The Task detail screen polls the core every two
seconds and reconstructs a step from a string. An append-only task event log
would give us, in one move: live steps without polling, a real audit trail of
what the agent did (which is a *privacy* feature — the user can see exactly
what happened), progress derived from events instead of a step-name heuristic,
replay after process death, and the wire format a remote worker would speak.

### 2.2 Ownership leases, heartbeats, orphan recovery

A run is owned by a worker holding a lease it renews by heartbeat. If the
worker dies, reconciliation takes the lease over, marks the run errored,
persists its receipts and publishes a terminal marker.

Our equivalent bug is concrete: kill the app mid-task and the row stays
`RUNNING` forever — Home will show it as an active task until the end of time.
The fix is small: stamp `updated_at` as a heartbeat (already on the row), and on
app start / worker wake reconcile any `RUNNING` task whose heartbeat is older
than a threshold into `FAILED` (or requeue it once).

### 2.3 Cancellation is a persisted request, not a method call

A cancel lands on any worker, is persisted, and the live owner observes it at
its next lease renewal; the first accepted action wins, dead owners fall back
to takeover. We have `Task.Status.CANCELLED` in the enum and **nothing that
sets it** — no cancel button, no way to stop a task you regret. Same shape
works for us: write the request, have the executor check it at step boundaries.

### 2.4 Artifacts have a boundary and a receipt

Outputs must be produced under a dedicated output directory, `present_files`
must present an output produced by *this* run, and a terminal delivery receipt
is recorded. That is V2 §26's download workspace with the details filled in:
task-scoped artifacts, verified before they reach user-visible Downloads.

### 2.5 Durable remote tasks

The gateway adapts an MCP server's `submit`/`status`/`cancel` into a durable
background task: remote id persisted before submit returns, polling with
leases, exponential backoff, bounded result storage, restart recovery. If we
ever poll a remote worker from a phone that sleeps, this is the shape.

### Not for us

LangGraph, the gateway topology, Redis stream bridges, container sandboxes,
multi-worker anything. We have one device and one WorkManager.

---

## 3. CowAgent — the remote worker

Covered in `COWAGENT_LEVERAGE.md`. In one paragraph: it is a mature build of
most of our V2 half (agent loop with a step budget, JSON-Schema tool contracts,
scheduler, tiered memory, sub-agents, MCP, steering/cancel) and it already
exposes an authenticated HTTP + SSE API. The play is to wire a **user-run**
instance into the `RemoteWorker` slot V2 §9 reserves — their machine, not a
developer proxy — and to port its `result` vs `display` split and scheduler
task model.

---

## 4. What we take, in order

| # | Take | From | Lands in | Spec |
|---|------|------|----------|------|
| 1 | Tool contract v2: JSON-Schema params, validated before execute; tier; result/display; `useless` | oh-my-pi + CowAgent | `agent/core/Tool.java`, `ToolRequest`, `ToolResult` | V2 §7, §23, §28 |
| 2 | Approval: tier × mode × per-tool override, with source + reason; Settings row; review sheet for CONFIRM | oh-my-pi | `policy/PolicyGate.java`, Settings, new confirm UI | V1 §9, V2 §11 |
| 3 | Task event log + a frozen contract (seq, categories, ignore-unknown) | DeerFlow | new `agent/tasks/TaskEventStore`, Task detail | V2 §14 |
| 4 | Heartbeat + reconciliation of orphaned runs; cancellation as a persisted request | DeerFlow | `TaskStore`, `TaskWorker`, Tasks UI | V1 §12, V2 §14 |
| 5 | Anchored page actions (refuse when the DOM moved) | oh-my-pi `hashline` | `BrowserEngine`, `BrowserTool` | V2 §7, §25 |
| 6 | Tool router picks the lightest tool; discoverable tools stay out of the prompt | oh-my-pi | `planner/` | V1 §6, V2 §6 |
| 7 | Sub-agent isolation for untrusted page content | CowAgent / oh-my-pi | `planner/` | V2 §24 |
| 8 | Optional remote worker (CowAgent instance the user runs) | CowAgent | `dispatcher/RemoteWorker.java` | V2 §9 |
| 9 | Artifact boundary + delivery receipt for downloads | DeerFlow | `DownloadTool`, workspace | V2 §26 |
| 10 | Skill format / MCP as external protocols — adopt, don't invent | CowAgent | later | V2 §6 |

Items 1–4 are the ones worth doing next: they are small, they are all
enforcement rather than features, and everything else in V2 (planning loop,
confirmation gates, schedules, remote work) sits on top of them.

## 5. What none of them solve for us

- **An agent that works with no model.** All three assume a frontier LLM is
  reachable. Our deterministic path is the floor, and must stay the default —
  the AI provider is opt-in and often "Local (on-device)".
- **Doze, background limits, battery.** WorkManager semantics are ours alone.
- **APK size.** Every idea above has to arrive as Java in a 20–45 MB budget,
  which rules out vendoring any of their runtimes.
- **On-device headless browsing.** Their browsers are desktop Chromium; V1 §8
  still needs its own measured decision.

## 6. Licensing

oh-my-pi (MIT, © Mario Zechner / can1357), DeerFlow (MIT, © ByteDance),
CowAgent (MIT, © zhayujie), Mr Nobody (MIT). Porting a *design* carries no
obligation; copying code does. Everything above is design. If we ever paste a
file, it goes in a `NOTICES` file with its copyright line.
