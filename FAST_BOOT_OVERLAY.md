# FAST Boot Overlay Framework Principles

> [!IMPORTANT]
> This document defines the **canonical rules** for the FAST bootstrap overlay. All code contributions must comply with these principles.

---

## 1. Canonical Chain Rule

**Only `FULL_SPV` writes to persistent chain state.**

| Operation | FULL_SPV | FAST_API_10POW |
|-----------|----------|----------------|
| Write to SPVBlockStore | ✅ Allowed | ❌ Forbidden |
| Update chainHead | ✅ Allowed | ❌ Forbidden |
| Perform rollback | ✅ Allowed | ❌ Forbidden |
| Modify wallet lastSeenBlockHeight | ✅ Allowed | ❌ Forbidden |
| Start/stop PeerGroup | ✅ Allowed | ❌ Forbidden |
| Delete blockstore file | ⚠️ User action only | ❌ Forbidden |

---

## 2. FAST_BOOT_STATE Machine

```
┌─────────────────────────────────────────────────────────┐
│                                                         │
│    IDLE ──────────► RUNNING ──────────► SUCCEEDED       │
│     ▲                   │                               │
│     │                   ▼                               │
│     │            DISABLED_SESSION                       │
│     │                   │                               │
│     │                   ▼                               │
│     └──────────── DISABLED_COOLDOWN                     │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### State Definitions

| State | Description | Transitions |
|-------|-------------|-------------|
| `IDLE` | Ready to attempt bootstrap | → `RUNNING` on start |
| `RUNNING` | Bootstrap in progress | → `SUCCEEDED` or `DISABLED_SESSION` |
| `SUCCEEDED` | Overlay active, data available | Terminal for session |
| `DISABLED_SESSION` | Failed, no retry this session | → `DISABLED_COOLDOWN` on app restart |
| `DISABLED_COOLDOWN` | Waiting before next attempt | → `IDLE` after cooldown expires |

### State Persistence

- `IDLE`, `RUNNING`, `SUCCEEDED`: Not persisted (session-only)
- `DISABLED_SESSION`: Persisted as session flag
- `DISABLED_COOLDOWN`: Persisted with timestamp

---

## 3. Failure-Safe Contract

When FAST bootstrap fails, the following **MUST** be guaranteed:

### ❌ MUST NOT
- Delete or recreate the blockstore file
- Reset chainHead to 0 or any height
- Stop PeerGroup
- Restart PeerGroup
- Call `wallet.reset()` or similar
- Modify any persistent chain state
- Throw exceptions that crash the app

### ✅ MUST
- Set `fastBootState = DISABLED_SESSION`
- Log failure with session ID and reason
- Allow SPV sync to continue unimpeded
- Set cooldown timestamp for future attempts

---

## 4. UI Data Source Policy

The UI displays data from either SPV or overlay, based on these rules:

### Height Display

```
if (fastBootState == SUCCEEDED && overlayHeight > spvHeight) {
    display = overlayHeight + " (API)"
} else {
    display = spvHeight + " (SPV)"
}
```

### Balance Display

```
if (fastBootState == SUCCEEDED && !spvFullySynced) {
    display = overlayBalance + " (pending SPV verification)"
} else {
    display = spvBalance
}
```

### Transaction List

```
if (fastBootState == SUCCEEDED && !spvFullySynced) {
    show overlay transactions with "unverified" badge
} else {
    show SPV-verified transactions only
}
```

### Switching Logic

- Overlay data is **always temporary**
- Once SPV catches up, overlay data is discarded
- SPV is always authoritative when synced

---

## 5. Runtime Guards

Code must include guards to prevent violations:

```java
// Example guard before any blockstore operation in FAST mode
if (syncMode == SyncMode.FAST_API_10POW) {
    log.error("FASTBOOT VIOLATION: Attempted blockstore write in FAST mode");
    return; // or throw IllegalStateException
}
```

### Guard Locations

1. `SPVBlockStore.put()` — guard against FAST writes
2. `BlockChain.setChainHead()` — guard FAST modifications
3. `PeerGroup.stop()` — guard FAST-triggered stops
4. Any blockstore file deletion code

---

## 6. Testing Checklist

When modifying FAST-related code, verify:

- [ ] FAST failure does not affect SPV blockstore
- [ ] FAST failure does not reset chainHead
- [ ] SPV continues syncing after FAST failure
- [ ] Overlay data only appears when FAST succeeds
- [ ] UI correctly shows data source (API vs SPV)
- [ ] Cooldown is respected between attempts

---

🔚 *End of Framework Principles*
