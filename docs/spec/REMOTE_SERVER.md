# Remote server — architecture, platforms, and integration

Status: **decision doc.** The client half (`DeviceIdentity` + `SignedRequest`) is
built and tested in this repository. This document pins the server half, which
lives in a **separate repository** (`mrnobody-server`) and verifies against the
device public key — it never holds a private key or an account.

The binding rule from `V2_ARCHITECTURE.md` §3 restated, because it is where a
privacy product starts lying: the remote worker **executes** tasks, so it sees
URLs and page content in plaintext. Transport encryption and anonymous identity
are real and claimable; "the server cannot see what your task does" is false and
must never be printed.

---

## 1. The contract the client already implements

`SignedRequest` (client) signs `nonce + '\n' + timestamp + '\n' + payload` with
the device's EC P-256 key (ECDSA / SHA-256). The server verifies three things,
in this order:

1. **Freshness** — `|now - timestamp| <= 5 minutes` (replay window).
2. **Integrity** — the signature covers the exact canonical bytes.
3. **Possession** — the signature verifies against the public key on file.

The public key, base64-encoded X.509 SubjectPublicKeyInfo, **is** the identity.
There is no username, password, or email. The server auto-registers an unseen
key with a zero credit balance on first contact.

---

## 2. Platform choices (free-first, portable)

The one constraint that drives everything: **the headless-browser worker pool is
the expensive part.** API, signature verification, queue and ledger are all
trivially free. Running Chromium 24/7 for real money is not — and that is what
remote execution is.

| Role | Free option(s) | Why |
|---|---|---|
| API + ECDSA verify + SSE streaming | **Cloudflare Workers** (Durable Objects for SSE) · Deno Deploy · Fly.io | No cold start, generous free tier, global |
| Database / credits ledger | **Supabase** (free Postgres + Realtime) · Neon (free Postgres) · Cloudflare D1 (free SQLite) · Turso | Ledger keyed by public key, no accounts |
| Queue | Cloudflare Queues · Upstash Redis (free) · CloudAMQP (free RabbitMQ) | |
| Push | **Firebase Cloud Messaging** | The only real Android push channel. Free. A delivery channel, not a database |
| Headless-browser worker pool | **Oracle Cloud Always Free** (4 ARM OCPUs / 24 GB RAM / 200 GB, never expires) · Fly.io Machines · a small VPS | The only genuinely always-on free home for Chromium |
| Payments | **Paystack** | Already in the docs; takes a transaction fee, not "free", but it is the rail |

**Reference implementation language: Node.js (TypeScript optional) + Playwright.**
Playwright is Node-native, so the worker that drives headless Chromium and the
API that verifies signatures can share one codebase and one `SignedRequest`
verifier. The queue/ledger are behind small interfaces so the in-memory/SQLite
reference swaps for Redis/Postgres in production without touching the rest.

### The privacy split (why this shape)

Plaintext page content flows through the **worker pool**, not the API. So:

- **Worker pool → IaaS you control** (Oracle's VM is *yours*). The sensitive
  execution stays off Google/Supabase infrastructure.
- **API / ledger / queue → SaaS** (Workers, Supabase, Queues). Cheap
  coordination layers; they only ever see envelopes and ledger metadata, never
  page content.
- **FCM = Google** sees push metadata (device token, timing), not task content.
  That is the trade for Android push, and it is stated, not hidden.

---

## 3. Reference architecture

```
Device ── signed request (EC P-256) ──► Cloudflare Workers (verify + SSE via Durable Objects)
                                              │
                                              └──► Queue (Cloudflare Queues / Upstash)
                                                        │
                                                        ▼
                                     Oracle Always-Free ARM VM(s) — headless Chromium worker pool
                                                        │
                                                        ▼
                                    Supabase (or D1) — task rows + credits ledger (keyed by public key)
Device ◄── SSE token stream ◄── worker ──┘
Device ◄── FCM "task finished" ◄── Firebase (push only)
Payments ──► Paystack ── webhook ──► credit the public key
```

Endpoints:

```
POST   /tasks                    signed request → verify → enqueue → { taskId }
GET    /tasks/:id                task state (signed)
GET    /tasks/:id/stream         SSE: token / done / error events
POST   /paystack/webhook         credit the public key in the payment reference
GET    /credits/:publicKey       balance (signed)
```

The SSE event shape is the same as the client's local `TaskStreamHub`
(`{taskId, type: token|done|error, text}`), so the Flutter task chat renders a
remote task with no new widget code.

---

## 4. Where the keys and env vars come from

| Env var | Where | Notes |
|---|---|---|
| `PORT` | any host | API listen port |
| `WORKER_SECRET` | your own `openssl rand -hex 32` | so only your worker pool can pull jobs |
| `DATABASE_URL` | **Neon** console.neon.tech → project → connection string, or **Supabase** app.supabase.com → Project Settings → Database → connection string | Postgres DSN |
| `SUPABASE_URL` / `SUPABASE_SERVICE_ROLE_KEY` | **Supabase** → Project Settings → API | only if using Supabase directly |
| `UPSTASH_REDIS_REST_URL` / `UPSTASH_REDIS_REST_TOKEN` | **Upstash** console.upstash.com → Redis → details | queue in prod |
| `CLOUDFLARE_ACCOUNT_ID` / `CLOUDFLARE_API_TOKEN` | **Cloudflare** dash.cloudflare.com → Workers & Pages; token at dash.cloudflare.com/profile/api-tokens | Workers deploy |
| `FCM_PROJECT_ID` / `GOOGLE_APPLICATION_CREDENTIALS` | **Firebase** console.firebase.google.com → project → Project Settings → Service accounts → "Generate new private key" | push |
| `PAYSTACK_SECRET_KEY` | **Paystack** dashboard.paystack.com → Settings → API Keys & Webhooks | live/secret key |
| `PAYSTACK_WEBHOOK_URL` | Paystack → Settings → API Keys & Webhooks → Webhook URL | points at your `/paystack/webhook` |
| (Oracle) SSH private key | **Oracle** cloud.oracle.com → Compute → instance → attach SSH key | access to the worker VM, not an "API key" |

`SignedRequest` verification needs **no external key** — it uses the device
public key sent with each request. The only signing material the server holds is
the `WORKER_SECRET` (internal, self-generated) and the Paystack secret.

---

## 5. Credits without accounts (from V2_ARCHITECTURE §4)

```
"Buy 100 credits" → Paystack checkout (payment reference carries the device public key)
                 → webhook → server → credits assigned to the public key
                 → spending = signed request "use N credits", verified against the key
```

Lost key = lost credits (anonymity and recovery are in tension; state it at
purchase). Refunds/abuse policy decided before taking money.

---

## 6. Build order (each step JVM/Node-testable before any device)

1. `DeviceIdentity` + `SignedRequest` — **done** (this repo).
2. **Server reference + cross-language signature fixture** — proves an Android
   client and the Node server agree on the exact canonical bytes and signature.
3. `RemoteWorker` for real — post signed request, stream SSE, forward to the
   already-built `TaskStreamHub`.
4. Worker pool (Playwright) + queue + ledger swap to production stores.
5. Credits + Paystack webhook.
