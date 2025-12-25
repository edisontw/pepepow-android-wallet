# FAST_API_10POW: Fast Usability Overlay (Tx→UTXO Snapshot)

## 1. Overview

**FAST_API_10POW** is a fast-bootstrap **overlay** for the PEPEPOW Android Wallet.

It provides **immediate spendability** by building an in-memory **Session Wallet** from explorer API data, using a **Tx→UTXO snapshot** approach.

> [!IMPORTANT]
> **FAST_API_10POW is NOT a sync mode.**
> It never writes to blockstore, never modifies chainHead, never performs rollback, and never touches `wallet.dat`.

---

## 2. Explorer API reality (constraints)

Available:

- `/ext/getaddresstxs/<addr>/<start>/<len>` → txid list + timestamp
- `/ext/gettx/<txid>` → vout(addresses, amount, confirmations)

Not available / unreliable:

- No UTXO endpoint
- No vin.prevout (cannot compute global spent)
- rawtransaction decrypt unusable

Therefore:

- **Global UTXO calculation is impossible**
- This release supports **new wallets only** (birth-time scoped snapshot)
- “Old wallet import/restore” is a **non-goal** until a server-side spent index exists

---

## 3. Two independent overlay lanes

### Lane A: PoW sampling (optional trust signal)

- Download latest header window (e.g., 1000 headers)
- Verify linkage / difficulty / chainwork (best effort)
- Randomly spot-check PoW for 10 blocks (when possible)
- Result affects only a trust indicator; must never block usability

### Lane B: Tx→UTXO snapshot (required)

For each wallet address:

1. Fetch txids via `getaddresstxs` with pagination
2. Stop when `tx.timestamp < walletBirthTimeMs`
3. For each txid, fetch `gettx`
4. For each `vout[i]` where `addresses` contains our address:
   - add outpoint `(txid, i)` to Session UTXO set

**Spent (local only):**
- When the app builds/signs an outgoing tx:
  - mark used inputs as spent immediately
  - add change outputs back into Session Wallet
- Never rely on explorer vin data for spent

**0-conf policy:**
- Incoming `confirmations == 0` → pending (not spendable)
- Outgoing locks inputs immediately

---

## 4. Session Wallet (in-memory only)

Used for:

- balance display
- history display (incoming from snapshot + outgoing from local journal)
- send enablement
- build/sign/broadcast tx (may reuse canonical keychain for signing)

Must NEVER:

- persist as canonical wallet state
- write to blockstore
- update SPV chain state

---

## 5. Failure-safe contract

On overlay failure:

- terminate the current attempt only
- transition state to `FAILED_TRANSIENT` or `DISABLED_PERMANENT`
- do NOT modify SPV state (even if SPV is enabled separately)

---

🔚 End of FAST_API_10POW documentation
