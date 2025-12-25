# FAST Boot Overlay Framework Principles (Canonical)

This document defines the **canonical rules** for the overlay framework.
All code contributions must comply.

> [!IMPORTANT]
> **FULL_SPV is the only canonical chain writer.**
> Overlays are bootstrap helpers and must be incapable of breaking SPV.

---

## 1. Canonical chain rule

Only `FULL_SPV` may:

- write blockstore
- modify chainHead
- rollback
- persist `wallet.dat`

Overlays (`FAST_API_10POW`, `API_1000POW`) must NEVER do any of the above.

---

## 2. Overlays are not sync modes

Overlays are **Bootstrap Overlays**:

- Speed up UI / usability only
- NEVER define chain validity
- NEVER mutate SPV state

---

## 3. Two independent overlay lanes

### Lane A: PoW sampling (optional)

Purpose: trust signal only. Must never block usability.

### Lane B: Tx→UTXO snapshot (required)

Purpose: build Session Wallet so balance/history/send work fast.

Snapshot success alone is enough to enable Send.

---

## 4. State machines

### SNAPSHOT_STATE (required)

- `IDLE`
- `RUNNING`
- `READY`
- `FAILED_TRANSIENT`
- `DISABLED_PERMANENT`

### POW_STATE (optional)

- `IDLE`
- `RUNNING`
- `SUCCEEDED`
- `FAILED_TRANSIENT`
- `DISABLED_PERMANENT`

---

## 5. UI data source router

`DATA_SOURCE` must be:

- `API_SESSION` when Session Wallet is `READY`
- otherwise `SPV_CANONICAL`

Rules:

- Never switch source based on PoW result
- All switches must be logged

---

## 6. Failure must be safe

Overlay failure MUST NOT:

- reset blockstore
- rollback chain
- stop PeerGroup
- restart sync
- force mode switch
- modify `wallet.dat`

Transient failures (timeout / IO / 5xx):

- end attempt
- retry on next onResume / next app start

Permanent disable ONLY when:

- invalid schema
- integrity failure
- wrong network/config

---

## 7. Required logging

Every run must include:

1. bootstrap entry `(mode, powState, snapshotState, lastRunTime)`
2. state transitions (pow + snapshot)
3. SPV start/stop (if SPV enabled separately)
4. SPV chainHead / bestHeight update (if SPV running)
5. UI source switch `(SPV_CANONICAL ↔ API_SESSION)`

Also include `FASTBOOT_SESSION_ID` in all overlay logs.

---

🔚 End of Framework Principles
