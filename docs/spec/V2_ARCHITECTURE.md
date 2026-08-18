# V2 Architecture — four independent systems

Status: **architecture of record.** Supersedes nothing; constrains everything.
Written down because the 70 MB privacy-engine work touches the same files as
agent execution, and the cheapest way to lose a working subsystem is to
refactor around it without knowing it was load-bearing.

```
                        MR NOBODY
                            │
                  ┌─────────┴─────────┐
                  │                   │
              Local Agent        Remote Agent
                  │                   │
            WorkManager          Remote Worker
                  │                   │
                  └─────────┬─────────┘
                            │
                       TaskStore
                            │
                     Device Identity
                            │
                  Secure Remote Channel
```

Four layers, deliberately independent:

| Layer | Owns | Must not know about |
|---|---|---|
| **Agent execution** | plans, tools, approval, budgets | which network route is active |
| **Task persistence** | TaskStore, TaskEventStore, heartbeat, schedule | whether a task is local or remote |
| **Device identity** | the keypair, request signing | payments, user identity |
| **Privacy routing** | DIRECT / PROXY / ORBOT / ARTI | tasks, credits, identity |

The rule that makes this worth writing down: **a change in one layer that
requires a change in another is a design error, not a task.** If enabling Tor
requires touching `TaskStore`, something has leaked.

---

## 1. What actually exists today

Naming this precisely, because the last verification report overclaimed and had
to be corrected.

| Piece | State |
|---|---|
| `TaskStore` (v3), heartbeat, schedule, event log | ✅ built, 88 tests |
| `TaskDispatcher` + `Worker` registry | ✅ built |
| `LocalWorker` → `DeterministicEngine` | ✅ built |
| `WorkManagerTaskScheduler` (one-shot + periodic) | ✅ built |
| `NetworkRoute` / `NetworkGate` chokepoint | ✅ built |
| `DirectRoute`, `ProxyRoute`, `OrbotTorRoute` | ⚠️ classes exist, **not device-verified** |
| `RemoteWorker` | 🔴 stub — always FAILs with "not enabled (V2)" |
| Device identity | 🔴 does not exist |
| Secure channel | 🔴 does not exist |
| Credits / payments | 🔴 does not exist |
| Embedded Arti | 🔴 not started, measure-first prototype only |

The dispatcher registry and the `RemoteWorker` stub are the two things that
make the remote layer additive rather than a rewrite. **Neither may be deleted
as dead code.** The stub is not dead; it is the seam, and it fails loudly and
honestly, which is the correct V1 behaviour.

---

## 2. Device identity

### 2.1 The correction: not Ed25519

The proposal says Ed25519/X25519. **Android Keystore's `KeyPairGenerator` does
not support them.** Its supported asymmetric algorithms are `EC` (NIST P-224 /
P-256 / P-384 / P-521) and `RSA`.

Curve 25519 appears in Android only as a *hardware feature version*:
`FEATURE_HARDWARE_KEYSTORE` version 200 advertises Ed25519 and X25519 support,
and `KeyProperties.KEY_ALGORITHM_XDH` exists but is `@hide`. That is a
device-dependent, non-public surface — not something to build an identity on.

This matters more than a naming preference, because of what would be lost:

> Using Ed25519 means generating the key **in software**, outside the Keystore.
> A key outside the Keystore is a key that can be read off the device. The
> entire value of this design — *the private key never leaves the device* — is
> the one property that choice destroys.

**Decision: EC P-256 in Android Keystore.**

- `KEY_ALGORITHM_EC` + `ECGenParameterSpec("secp256r1")`
- `PURPOSE_SIGN` for identity proof (ECDSA / SHA-256)
- `PURPOSE_AGREE_KEY` for ECDH session keys — added in **Android 12 (API 31)**,
  which is exactly this app's `minSdk = 31`. No compatibility gap.
- Hardware-backed where the device allows; `KeyInfo.getSecurityLevel()` is the
  only honest way to know, and it must be **reported, not assumed**.

P-256 is not a downgrade in strength. It is a downgrade in fashion, and an
upgrade in the only property the user actually asked for.

If a device reports no hardware backing, the key still works — it is simply
software-protected, and the UI must not claim otherwise. Same rule as
`ProfileManager.isSupported()`: feature-detect, degrade the *wording*, never
the check.

### 2.2 One installation = one identity

Not "one-time signature". A long-lived keypair, generated at first run:

```
first run
   ↓
generate EC P-256 keypair in Android Keystore
   ↓
private key  → Keystore, non-exportable
public key   → the device identity
```

Authentication is proof of possession, per session, with a server nonce:

```
Device ──── public key + signature over server nonce ────► Remote Worker
                                                                │
                                              verify against public key
                                                                │
Device ◄──────────── authenticated session ─────────────────────┘
```

Per-task session keys derive from ECDH, so a compromised session key does not
retroactively open earlier tasks.

### 2.3 It identifies an installation, not a person

The identity asserts exactly one thing:

> *This installation owns these tasks.*

Not "this is Bobby". Concretely, this forbids:

- no account, email, phone, username, password
- IP is not identity — changing Wi-Fi or mobile network is invisible
- no cross-installation linkage
- **the server must never be handed anything that identifies the human**

Clear app data ⇒ the key is gone ⇒ the identity is gone. That is correct
behaviour, not a bug. Migration/recovery, if it ever exists, is a **separate,
explicitly opt-in, encrypted export** — never an implicit backup, and never
something that silently makes the identity portable.

> ⚠️ **Android caveat to handle before shipping:** auto-backup can copy app
> data off-device. Keystore private keys are not backed up (they cannot be),
> but anything *derived* from them must not be either. The identity store needs
> explicit backup exclusion, or "the key never leaves the device" acquires an
> asterisk nobody reads.

---

## 3. What the remote worker can and cannot promise

This is where a privacy product usually starts lying, so it is written down
before the code exists.

The remote worker **executes** tasks: it browses, it fetches, it downloads. To
do that it must see URLs, page content, and results **in plaintext**. Encrypting
the transport does not change this.

| Claim | True? |
|---|---|
| Transport is encrypted device↔worker | ✅ yes |
| Server cannot identify the human | ✅ yes, by construction |
| Results encrypted at rest, to the device key | ✅ achievable |
| **Server cannot see what the task does** | 🔴 **false — do not claim it** |

So the honest line is:

> *Local tasks stay on your device. Remote tasks run on our infrastructure,
> which can see what the task does — it has to, in order to do it. It never
> learns who you are.*

Anything stronger — "zero-knowledge", "end-to-end encrypted tasks" — would be
the same category of overclaim as "private tabs: isolated storage, cleared on
close", which was marked 🔴 for exactly this reason. **Local-by-default is the
privacy story. Remote is a paid convenience with a stated cost.**

---

## 4. Payments without accounts

Payment identity and app identity stay separate:

```
PAYSTACK          payment reference + receipt email
MR NOBODY         anonymous device public key
REMOTE WORKER     task + device public key
```

Flow:

```
"Buy 100 credits"
   ↓
payment reference, carrying the device public key in metadata
   ↓
Paystack checkout
   ↓
webhook → server
   ↓
credits assigned to the public key
```

Spending is a signed request: *"use 5 credits"*, signed by the device key,
verified against the public key on file. No account, no session cookie, no
login.

Paystack's standard transaction init requires an email. That is fine **provided
it is scoped as a payment receipt, not an identity** — collected by the payment
provider, never used as the Mr Nobody identifier, never joined to the device
key beyond what the webhook needs to credit it.

**Credits, not subscriptions, initially.** A subscription promises unlimited
work at a fixed price against costs that are per-task — LLM tokens, browser
minutes, bandwidth. One heavy user destroys the margin. Credits price the thing
that actually costs money.

Two open problems, stated rather than hidden:

1. **Lost key = lost credits.** Anonymity and recovery are in direct tension:
   any recovery mechanism is, definitionally, a way to prove you are the same
   person without the key. The options are (a) accept the loss and say so
   loudly *before* purchase, or (b) offer an explicit encrypted export the user
   chooses to keep. Not both by accident.
2. **Refunds and abuse** both normally need an account. Decide deliberately
   what is possible without one, before taking money.

---

## 5. Scaling

Render is the execution layer, not one server running everyone's agent.

```
users → API server → task queue → worker pool → task DB
```

10 users → 1 worker. 1,000 → a scaled pool. Autoscaling on Pro+; manual scaling
otherwise. Workers are stateless; the queue and the DB hold state.

Pricing tiers follow cost, not feature envy:

- **Free** — unlimited *local* agent, basic search, a small remote allowance
- **Pro** — more and longer remote runs, more concurrency
- **Credits** — heavy browser automation, large downloads, expensive models

> *Mr Nobody is free and local. You pay when you ask Mr Nobody's infrastructure
> to work for you.*

---

## 6. What the privacy-engine work must not break

The preservation contract. Each line is a thing that exists and is easy to
delete by accident while chasing 70 MB.

- **`NetworkGate` stays the single egress chokepoint.** CI already fails on any
  `openConnection(` outside it. A new HTTP path for the privacy engine does not
  get an exemption.
- **`NetworkRoute` stays an interface with swappable implementations.**
  Embedded Arti, if it ever ships, is one more implementation behind it — not a
  rewrite of the routing layer.
- **`RemoteWorker` and the dispatcher registry stay**, stub included.
- **`TaskStore` migrations are cumulative and additive.** It holds user data;
  `onUpgrade` must never become drop-and-create.
- **Task execution must not learn which route is active.** If a tool needs to
  know it is on Tor, the abstraction has failed.
- **Heartbeat, schedule, session scope, spilling, anchors, budgets stay wired.**
  They were inert for one commit and that was one commit too many.
- **Privacy audit, filter-digest gate, and the 70 MB APK gate keep passing.**

---

## 7. Build order

Dependencies, not preferences. Each step is testable on a JVM before any device
is involved.

1. **`DeviceIdentity`** — EC P-256 keypair, Keystore-backed, honest
   `securityLevel` reporting. Pure enough to unit-test the encoding and the
   signing envelope; the Keystore call itself is one thin seam.
2. **Signed request envelope** — nonce, timestamp, signature, replay window.
   Fully testable without a network.
3. **Server-side verification** — separate repo/service; not this codebase.
4. **`RemoteWorker` for real** — replaces the stub, behind the existing
   `Worker` interface. Local remains the default.
5. **Credits** — only after 1–4, because a credit balance signed by a key that
   does not exist yet is not a feature.

Privacy routing (Arti, fingerprinting, DNS) proceeds **in parallel and
independently**. That is the point of the four layers: neither track blocks the
other, and neither is allowed to reach into the other's files.
