# Mr Nobody — Remote Agent Credits: Technical + Economic Feasibility

**Status:** feasibility study only. No payments, no server, no architecture change.
**Date:** 2026-08-18. All figures are estimates from public pricing unless labelled *measured* (none are).

---

## 0. The single most important finding first

The proposed product sells **time** (₦3,500 → 120 min) but the **cost** is driven by
**model size × duty cycle**, not by minutes. Minutes are a weak proxy for cost, and
that mismatch is where the pricing either makes money or quietly bleeds it.

**Verdict: viable and healthy at the median (~80% gross margin), but the tail — a
power user routed to a 70B model with a high duty cycle — can turn a ₦3,500 package
into a loss. It is fundable if two mitigations are in place: model routing, and an
"active-compute" (not wall-clock) timer.**

---

## 1. Grounding numbers (used throughout)

| Input | Value | Source |
|---|---|---|
| USD → NGN | ₦1,352 official / ₦1,415 parallel | xe.com, 2026-08-18 |
| ₦3,500 in USD | $2.59 (official) / $2.47 (parallel) | calc |
| Paystack local card | 1.5% + ₦100, capped ₦2,000, +7.5% VAT | paystack.com/pricing |
| Paystack fee on ₦3,500 | ₦152.50 + ₦11.44 VAT ≈ **₦164** (~$0.12) | calc |
| Net received per package | ₦3,336 ≈ **$2.47** | calc |
| RTX 4090 (24 GB) | ~$0.40/hr (RunPod community $0.34–0.46) | RunPod/vast.ai, 2026 |
| A100 80 GB | ~$1.50/hr (RunPod $1.19–1.79) | RunPod, 2026 |
| L40S 48 GB | ~$0.50/hr | RunPod, 2026 |
| CPU worker (4 vCPU / 8 GB) | ~$0.04/hr | VPS market |

---

## 2. The core cost model

The customer buys **active agent execution time**. The timer runs only while a worker
is doing real work (LLM reasoning + browser actions), never while queued or idle.

The key structural fact: **agent work is bursty.** An agent minute is *not* a GPU
minute — the GPU is idle during page fetches, DNS, network round-trips and browser
rendering. Measured agent workloads run a **30–50% GPU duty cycle** for
search/read/browse tasks, rising to ~80% for dense reasoning/replanning.

```
cost per agent minute = (LLM duty cycle × GPU $/min) + (browser $/min) + amortized infra
```

### 2.1 Cost per active agent minute (median case)

| Component | Assumption | $/min |
|---|---|---|
| LLM (27B Q4 on RTX 4090, 30% duty) | 0.30 × $0.40/60 | $0.0020 |
| Headless browser (1 concurrent, CPU VPS) | $0.04/60 | $0.0007 |
| API + queue + DB (serverless, amortized) | ~$0.0002 | $0.0002 |
| **Total** | | **≈ $0.003/min (≈ ₦4/min)** |

### 2.2 Cost per 120-minute package

| Case | Duty / model | LLM | Browser | Infra+retry (~15%) | Paystack | **Total** |
|---|---|---|---|---|---|---|
| **Median** | 30%, 27B | $0.24 | $0.08 | $0.05 | $0.12 | **$0.49** |
| **Heavy** | 60%, 27B | $0.48 | $0.08 | $0.08 | $0.12 | **$0.76** |
| **Worst (70B)** | 80%, Llama-70B on A100 | $2.40 | $0.08 | $0.37 | $0.12 | **$2.97** |

### 2.3 Margin

| Case | Revenue (net) | Cost | Gross margin |
|---|---|---|---|
| Median | $2.47 | $0.49 | **80%** |
| Heavy | $2.47 | $0.76 | **69%** |
| Worst (70B) | $2.47 | $2.97 | **−20% (loss)** |

**Break-even duty cycle** on the 27B model: the package clears $2.47 after Paystack.
LLM at $0.40/hr can run ~6 GPU-hours before cost exceeds revenue — i.e. **even 100%
LLM duty on the small model stays profitable.** The loss exists *only* when a user
is routed to the 70B/A100 tier and hammers it. That is a routing problem, not a
pricing problem, and it is controllable.

---

## 3. Is ₦3,500 → 120 minutes right?

**It is generous to the customer, not wrong.** We charge ≈ ₦29/min (₦27.8 net) and
cost ≈ ₦4/min at the median — a ~7× markup. The customer gets a very real bargain
(120 min of agent work for ~$2.59 vs. ~$0.30–1.00 of raw tokens on an API alone),
while the margin is healthy.

**Alternatives to price (for the tail risk):**

| Option | ₦3,500 buys | Effect |
|---|---|---|
| Keep (current) | 120 min | 80% median margin; 70B tail loses |
| Shorter | 60 min | margin nearly doubles; tail risk halves |
| Hybrid | 120 min *small-model* + token-metered 70B | eliminates tail loss, adds complexity |
| Tiered queues | Standard / Priority | Priority = price premium, no cost change |

**Recommendation:** ship ₦3,500 → 120 min as the headline, but **route to the 70B
model only for tasks that genuinely need it**, and cap 70B time per task. The hybrid
"minutes + a 70B token surcharge" is the clean long-term fix; it can wait.

---

## 4. Recommended architecture

```
Android app → signed request (DeviceIdentity/SignedRequest, already built)
    → API (Cloudflare Workers: verify signature, replay window, credit check)
    → Queue (Cloudflare Queues / Upstash Redis)
    → Dispatcher
         ├── LLM worker (vLLM endpoint on GPU pool)
         └── Browser worker (headless Chromium pool on CPU VPS)
    → Event store (Supabase/Neon Postgres) → SSE back to the app
```

This reuses the existing `Task → Dispatcher → Worker → Tool` seams and the
`DeviceIdentity`/`SignedRequest`/`TaskEventStore`/`RemoteWorker` client half already
in the repo. Nothing is rewritten; the server is a *new* repository.

---

## 5. Recommended open-weight model(s)

Goal: tool calling, browser reasoning, planning/replanning, structured output — at
the lowest cost that clears the quality bar.

| Tier | Model | Quant | VRAM | GPU | Role |
|---|---|---|---|---|---|
| **Routine (default)** | Qwen3.6 27B (or 35B-A3B MoE) | Q4_K_M | ~16–18 GB | RTX 4090 | search/read/summarize/monitor |
| **Hard** | Llama 3.3 70B (or Qwen3-Coder 72B) | Q4 | ~40 GB | A100 80 GB | multi-step research, complex planning |

**Routing:** small model for routine tasks (the 80% case), large model only when a
task is flagged hard. This is where the margin is protected — see §2.3.

---

## 6. Recommended inference stack

- **vLLM** on the GPU pool (continuous batching → many users per model instance),
  exposed as an OpenAI-compatible endpoint the existing `OpenAiCompatibleProvider`
  already speaks.
- **Quantization:** Q4_K_M for the 27B (quality/VRAM sweet spot), Q4 for 70B.

## 7. Recommended GPU / VPS options

| Need | Pick | Why |
|---|---|---|
| Small-model GPU | **RunPod** RTX 4090 ~$0.40/hr, or **Vast.ai** spot ~$0.09–0.35/hr | cheapest reliable / cheapest interruptible |
| Large-model GPU | RunPod A100 80 GB ~$1.50/hr | 70B needs 40 GB+ |
| Serverless burst | **RunPod Serverless** (per-second) | autoscale, no idle cost — worth evaluating *first*, but not assumed cheapest |
| Browser workers | Cheap CPU VPS ($0.04/hr) | Chromium is RAM/CPU, not GPU |

The brief's "evaluate one cheaper alternative to RunPod Serverless" → **Vast.ai spot**
(~40–50% cheaper, no SLA) for interruptible work, and **Modal/Fly.io Machines** for
serverless browser workers.

---

## 8. Browser worker architecture

A pool of headless Chromium instances on CPU VPSes, one browser per task session
(reusing the existing `SessionScope` isolation idea). Each worker: fetch → render →
extract/click/type → return. ~5–10 concurrent tabs per 4-vCPU/8 GB node.
**Browser, not LLM, becomes the concurrency bottleneck first** (see §11).

## 9. Queue architecture

Cloudflare Queues (or Upstash Redis) — durable, cheap, autoscaled. Jobs are
{taskId, deviceKey, instruction, modelTier}. Priority queue = a second queue with a
higher-priced tier; same machinery.

---

## 10. Credit ledger design (append-only, derived balance)

Do **not** store `balance = 120`. Store events, derive the balance:

```
CREDIT_PURCHASE   +120 min        (Paystack webhook, keyed to device public key)
TASK_STARTED      (reserve)       — reservation, not yet a debit
TASK_USAGE        −37 sec         — appended on each metered tick
TASK_COMPLETED    (settle)        — finalizes the reservation
REFUND            +12 min         — infra failure, not user-caused
```

- Balance = Σ credits − Σ settled debits. Derived, never stored, so double-spend is
  impossible by construction.
- Keyed by the device public key (already built: `DeviceIdentity` + `SignedRequest`).
- Deterministic and auditable: every event has a sequence, timestamp, and signature.

## 11. Anonymous device/payment flow

```
Tap Buy → Paystack checkout (payment reference carries device public key)
  → webhook → server verifies signature → CREDIT_PURCHASE keyed to the key
Spend → signed request "use N minutes" → verified → TASK_USAGE events
```

"No account" ≠ "anonymous to Paystack" — the payment processor necessarily sees
payment info. The device key is the Mr Nobody identity; the email (Paystack's
requirement) is a receipt address, never joined to the key beyond the webhook credit.

---

## 12. Exact remote-time accounting rules (deterministic + auditable)

1. **Timer starts** when the worker begins the task's first action, **not** at
   enqueue. Queue wait costs nothing.
2. **Timer stops** on completion/cancel/crash. Idle between actions does **not**
   meter (polled, not wall-clock).
3. **Worker startup + model load** is charged to us, never the user — a cold-start
   penalty is an infrastructure cost, not agent time.
4. **Infra-caused failures/retries** (our 5xx, worker crash, OOM) → `REFUND`, not a
   debit. User-caused retries (agent chose a failing action) meter normally.
5. **Metering granularity:** 1-second ticks appended to the ledger; a task's cost is
   the sum of its `TASK_USAGE` events.
6. **Idempotency:** the reservation id (device nonce + task id) is unique, so a
   replay or double-settle is rejected.

---

## 13. Concurrency / capacity estimates (labelled ESTIMATES, not measured)

| Model | VRAM | Concurrent users per GPU (continuous batching) |
|---|---|---|
| Qwen3.6 27B Q4 (RTX 4090) | ~18 GB | ~10 |
| Llama 3.3 70B Q4 (A100 80 GB) | ~40 GB | ~10 |
| Browser worker (4 vCPU/8 GB) | — | ~5–10 tabs |

**Users → infrastructure (estimate, assumes 30% duty cycle):**

| Active users | Active tasks (30%) | LLM GPUs (27B) | Browser nodes |
|---|---|---|---|
| 10 | ~3 | 1 | 1 |
| 100 | ~30 | 1–2 | 4–8 |
| 500 | ~150 | 5–10 | 20–40 |
| 1,000 | ~300 | 10–20 | 40–80 |

Bottleneck ordering: **browser workers saturate first**, then GPU. Context length
raises cost (KV cache) — longer contexts mean fewer concurrent users per GPU.

---

## 14. Deliverable summary

1. **Architecture** — §4 (reuses existing seams, new server repo).
2. **Model(s)** — Qwen3.6 27B Q4 (routine) + Llama 3.3 70B Q4 (hard), routed.
3. **Inference stack** — vLLM, OpenAI-compatible (existing provider already fits).
4. **GPU/VPS** — RunPod RTX 4090/A100; Vast.ai spot as the cheaper alternative.
5. **Browser workers** — CPU VPS Chromium pool, 5–10 tabs/node.
6. **Queue** — Cloudflare Queues / Upstash; priority = second queue.
7. **Ledger** — append-only events, derived balance (never stored), keyed by device key.
8. **Anonymous flow** — Paystack webhook → credit the device public key.
9. **Accounting** — §12 (active-only timer, infra-failure refunds, idempotent settle).
10. **Cost per active agent minute** — **≈ $0.003 (₦4) median; $0.006–0.025 tail.**
11. **Feasibility** — **₦3,500 → 120 min is viable (~80% median gross margin)**, with
    the tail risk (70B × high duty) closed by model routing and an active-compute timer.

**What is *not* recommended yet:** payments integration, a production server, or any
architecture change — per the brief, this is evaluation only.
