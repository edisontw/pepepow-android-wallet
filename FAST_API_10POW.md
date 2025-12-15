# FAST_API_10POW: Fast Bootstrap Overlay

## 1. Overview

**FAST_API_10POW** is a fast-bootstrap **UI overlay** for the PepePow Android Wallet. It provides instant display of chain height, balance, and recent transactions while the canonical SPV sync runs in the background.

> [!IMPORTANT]
> **FAST_API_10POW is NOT a sync mode.** It does not write to the blockstore, modify chainHead, or affect SPV consensus state. Only `FULL_SPV` manages the canonical chain.

### Key Principles

| Aspect | Behavior |
|--------|----------|
| **Data Source** | Explorer API snapshot |
| **Writes to Blockstore** | ❌ Never |
| **Modifies ChainHead** | ❌ Never |
| **Affects Wallet State** | ❌ Never |
| **Purpose** | UI display overlay only |

---

## 2. Architecture & Workflow

### 2.1 Overlay Bootstrap Process

1. **API Height Discovery**  
   Query explorer API for: current tip height, block hash, difficulty, chainwork

2. **Header Window Extraction (Tip−1000)**  
   Download the most recent 1000 block headers from API

3. **Window Validation**  
   - Structural validation for each header
   - Parent→child linkage checks
   - Difficulty target validation per header
   - Cumulative chainwork reconstruction

4. **PoW Spot-Verification (10 Random Blocks)**  
   Select 10 random blocks within the window and perform full PoW hash validation

5. **Overlay Snapshot Created** *(not written to blockstore)*  
   If PoW sampling passes, overlay data is stored in memory-only variables for UI display

6. **FULL_SPV Runs Independently**  
   SPV sync starts/continues regardless of overlay result — it is never affected by FAST bootstrap

### 2.2 State Machine: FAST_BOOT_STATE

```
IDLE → RUNNING → SUCCEEDED
              ↘ DISABLED_SESSION → DISABLED_COOLDOWN → IDLE
```

| State | Description |
|-------|-------------|
| `IDLE` | Not running, ready to attempt |
| `RUNNING` | Bootstrap in progress |
| `SUCCEEDED` | Overlay data available for UI |
| `DISABLED_SESSION` | Failed this session, will not retry |
| `DISABLED_COOLDOWN` | Cooldown period before next attempt |

---

## 3. Comparison With Traditional SPV

| Feature | Traditional SPV | FAST_API_10POW Overlay |
|---------|-----------------|------------------------|
| Header Source | P2P only | API snapshot (UI only) |
| Initial Display Speed | Slow, waits for sync | Instant |
| PoW Verification | Per block | 10-block random spot-check |
| Writes to Blockstore | ✅ Yes | ❌ No |
| Canonical Chain | ✅ Yes | ❌ No (overlay only) |
| Trust Model | Pure PoW trust | API-assisted display |

---

## 4. Security Model

### 4.1 Threat Scenarios

The main threat is a compromised API providing a fake 1000-header chain.

To succeed, an attacker must:
- **(A)** Forge valid difficulty-adjusted headers with correct parent→child linkage
- **(B)** Pass 10 random PoW spot-verification checks

### 4.2 Why This Is Safe

- Forging a single valid PoW block is computationally expensive
- Forging 10 random blocks simultaneously is **astronomically improbable**
- Even if overlay is fooled, **SPV canonical chain is unaffected**
- Worst case: UI shows incorrect data temporarily; SPV will correct it

### 4.3 Why 1000 Headers?

- Sufficient difficulty variation for meaningful validation
- Meaningful chainwork reconstruction
- Low correlation for random PoW selection
- Comparable to Bitcoin SPV checkpoint spacing

---

## 5. Common Misconceptions

> [!WARNING]
> **Misconception**: "FAST success means SPV is synced to tip"  
> **Reality**: FAST success only means overlay UI data is ready. SPV sync continues independently and may be behind.

> [!WARNING]
> **Misconception**: "FAST failure means I need to reset/reinstall/switch modes"  
> **Reality**: FAST failure only disables the overlay for this session. SPV sync continues normally — no action required.

> [!WARNING]
> **Misconception**: "FAST writes the API headers to the blockstore"  
> **Reality**: FAST never writes anything to the blockstore. Only `FULL_SPV` manages persistent chain state.

---

## 6. When to Use Each Mode

| Use Case | Recommended Mode |
|----------|------------------|
| Normal wallet usage | `FAST_API_10POW` (overlay) + `FULL_SPV` (canonical) |
| Validating historical PoW | `FULL_SPV` only |
| Building archival nodes | `FULL_SPV` only |
| Forensic analysis | `FULL_SPV` only |
| Testing consensus changes | `FULL_SPV` only |

---

## 7. Failure-Safe Contract

FAST_API_10POW **MUST NEVER**:
- Delete or recreate the blockstore
- Reset chainHead to 0 or any other height
- Stop or restart PeerGroup
- Modify wallet lastSeenBlockHeight
- Trigger rollback operations

On failure, FAST **MUST**:
- Set state to `DISABLED_SESSION`
- Log the failure clearly
- Allow SPV to continue unimpeded

---

🔚 *End of FAST_API_10POW Documentation*