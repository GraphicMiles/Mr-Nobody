# What CowAgent gives us

> Part of the harness survey — see `AGENT_HARNESS_SURVEY.md` for how this sits
> next to oh-my-pi (tool contract, approval model) and DeerFlow (long-horizon
> run discipline).

`github.com/GraphicMiles/CowAgent` — MIT, Python, actively developed (upstream
`zhayujie/CowAgent`). It calls itself "a reference implementation of Agent
Harness engineering", and that is a fair description: it is a mature build of
roughly the half of our V2 spec we have not written yet.

Both projects are MIT, so borrowing code or design is legally clean as long as
we keep the notice.

## 1. What it actually contains

| Area | CowAgent | Our V2 item |
|------|----------|-------------|
| Agent loop | `agent/protocol/agent.py`, `agent_stream.py` — tool-calling loop with a step budget (`max_steps`), context-window accounting and trimming, streamed step events | §5 Agent Pipeline, §14 |
| Tool contract | `agent/tools/base_tool.py` — `name` / `description` / `params` (JSON Schema), availability probe, `ToolStage` (pre/post), `ToolResult(status, result, ext_data, display)` | §7 typed actions, §28 interfaces |
| Tool set | bash, browser (Playwright), read/write/edit/ls/search_files, web_fetch, web_search, memory, scheduler, subagent, vision, send, mcp | §6 Tool Router |
| Scheduling | `agent/tools/scheduler/` — `task_store.py`, `scheduler_service.py`, integration | §16 Scheduled tasks, §17 Monitoring |
| Memory | `agent/memory/` — three tiers, chunker, embeddings, hybrid keyword+vector search, "Deep Dream" distillation | §18 Agent Memory |
| Knowledge | `agent/knowledge/` — Markdown wiki + graph | beyond V2 |
| Skills | `skills/*/SKILL.md` + Skill Hub install | beyond V2, but a ready-made packaging format |
| Sub-agents | `agent/subagent/` — child agent with its own context | §24 prompt-injection containment |
| MCP | `agent/tools/mcp/` — client, OAuth, tool retrieval | §6, cheap tool ecosystem |
| Steering / cancel | `agent/protocol/steer.py`, `cancel.py` — mid-run user instructions, cancellation | §11 Human confirmation |
| Remote API | `channel/web/web_channel.py` — HTTP + SSE, HMAC bearer tokens: `/message`, `/stream`, `/cancel`, `/api/scheduler/*`, `/api/sessions/*`, `/api/tools`, `/api/skills`, `/api/memory` | §9 Remote worker |

## 2. The one big integration: CowAgent as our RemoteWorker

V2 §9 already reserves a slot for exactly this:

```
Android → encrypted task/session → user-controlled or trusted worker → headless browser → website
```

and our `agent/dispatcher/RemoteWorker.java` is currently a stub that fails
every task with *"Remote worker is not enabled (V2)"*.

CowAgent can fill that slot as-is, because it already exposes an authenticated
HTTP API with SSE streaming. The shape:

```
Mr Nobody (phone)                         CowAgent (user's PC / their own VPS)
  Task created, saved to TaskStore
        |  POST /message  { instruction, session }   Bearer <token>
        |------------------------------------------>
        |  GET /stream?request_id=…  (SSE)           plan → tool calls → result
        |<------------------------------------------
  Task.currentStep / progress updated per event
        |  POST /cancel                              user cancelled
        |------------------------------------------>
  Result + artifacts stored locally
```

Why this fits our constraints rather than fighting them:

- **No developer proxy.** The endpoint is a machine the *user* runs. V1 §15's
  "not: user → developer proxy → website" stays true.
- **Disclosure is already our pattern.** The AI-provider screen exists and says
  "task context may leave the device"; a remote worker is the same consent
  shape, one row lower.
- **Nothing grows the APK.** Playwright, embeddings, the whole Python stack
  stays off the phone — directly serving the 20–45 MB target and the "no giant
  bundled engine" non-goal.
- **The heavy half of V2 becomes optional.** Multi-step planning, a real
  browser, memory and skills work *today* for a user who runs the worker, while
  the on-device deterministic path keeps working for everyone else. That is
  exactly the local/remote split V2 §9 asks for.

Concrete first slice (small, self-contained):

1. `CowAgentWorker implements Worker` — POST `/message`, consume `/stream`,
   map events onto `Task.setCurrentStep/ setResult / setError`, honour
   `/cancel`.
2. Settings → Agent → **Remote worker**: base URL + token + a test button, with
   the same disclosure copy pattern as the AI provider row.
3. `TaskDispatcher` picks `remote` only when configured and reachable;
   otherwise it stays local. Never silent: the task detail screen already has a
   "worker: on-device / remote" line.

## 3. What to port rather than call

Independent of the remote worker, four ideas transfer straight into the Java
core and are worth stealing:

1. **`result` vs `display` on every tool result.** CowAgent's `ToolResult`
   carries a model-readable payload and a human-readable rendering. We learned
   the same lesson the hard way (commit `5bbb925`, "parse search results instead
   of dumping page scrape"); making it a field instead of a convention prevents
   the regression class entirely.
2. **JSON-Schema `params` on tools + validation before execution.** Our
   `ToolRequest` is a `Map<String,String>`. Schema-first is what makes V2 §23's
   "LLM output is data, not authority" enforceable instead of aspirational.
3. **Sub-agent isolation for untrusted page content.** Reading a hostile page in
   a child agent whose output is a typed summary — not instructions — is the
   most practical prompt-injection defence in V2 §24, and CowAgent already has
   the runner shape.
4. **Scheduler task model** (`frequency`, `next_run`, `enabled`, catch-up after
   a missed window — see their midnight-rollover fix) mapped onto WorkManager
   gives us V2 §16 without designing it from scratch.

Also worth adopting eventually: **MCP** as our external tool protocol, and the
**`SKILL.md`** format, so we inherit an ecosystem instead of inventing one.

## 4. What not to do

- **Don't embed CowAgent in the APK.** Python + Playwright + a desktop console
  is the opposite of a 20–45 MB privacy browser, and it would violate the
  "no giant bundled engine" non-goal.
- **Don't make the remote worker the default or a requirement.** V1 §2:
  no mandatory backend for normal browsing. Local stays the default; remote is
  opt-in, disclosed, and revocable.
- **Don't copy its trust model wholesale.** CowAgent is built for a machine its
  owner already trusts, so tools like `bash` are broadly capable by design. Our
  `PolicyGate` (ALLOW / CONFIRM / DENY) must sit in front of anything a remote
  worker is allowed to do on our behalf, and side-effecting actions still need
  the confirmation gate from V2 §11.
- **Don't let the phone accept arbitrary worker output as authority.** Treat
  worker responses like page content: data to be validated against the task's
  permissions, not commands.

## 5. Honest limits

- CowAgent's browser tool drives Playwright/Chrome on a desktop. It does not
  help our *on-device* headless engine question (V1 §8 / V2 §8) at all — that
  decision still needs its own benchmark.
- Its memory/knowledge stack assumes an embeddings provider and a filesystem;
  on-device we would need a much smaller design, so port the *tiering* idea,
  not the implementation.
- It is a large, fast-moving codebase in a different language. Every line we
  copy is a line we maintain in translation — prefer calling it over the wire,
  and port only the four patterns in §3.
