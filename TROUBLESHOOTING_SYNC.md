# Troubleshooting: Overlay / Sync Issues

This guide helps diagnose **FAST/API overlay** issues versus **FULL_SPV** issues.

> [!IMPORTANT]
> In this release, **FULL_SPV may be gated and not auto-running by design**. If you do not explicitly enable it, you may not see any PeerGroup/SPV logs.

---

## 1. “Balance / History looks wrong”

### First: identify data source (API_SESSION vs SPV_CANONICAL)

Search logs:

```bash
adb logcat | grep -E "FASTBOOT|SNAPSHOT|DATA_SOURCE|SPV|PeerGroup|BlockChain"
```

Typical patterns:

- **Overlay Session Wallet active**:
  - `snapshotState=READY`
  - `DATA_SOURCE=API_SESSION`
- **SPV UI active**:
  - `DATA_SOURCE=SPV_CANONICAL`
  - PeerGroup / BlockChain logs may appear (only if FULL_SPV is enabled)

---

## 2. “Overlay appears stuck / failed”

### What “failed” means here

Overlay failure is **safe**: it only disables the overlay for the current attempt/session (or until cooldown).
It must never delete blockstore, rollback, or affect SPV state.

Check for:

```bash
adb logcat | grep -E "\[FASTBOOT-4\]|SNAPSHOT_STATE|FASTBOOT-COOLDOWN"
```

Common causes:

| Symptom | Likely cause | What to do |
|--------|--------------|------------|
| `SNAPSHOT_STATE=FAILED_TRANSIENT` | API timeout / IO / 5xx | Re-open app or onResume triggers retry |
| `SNAPSHOT_STATE=DISABLED_PERMANENT` | schema mismatch / integrity failure / wrong network | Fix config or explorer compatibility |
| `FASTBOOT-4 result=FAILED` | PoW sampling lane failed | Snapshot lane may still succeed; Send should still be possible if snapshot is READY |

---

## 3. “SPV appears stuck” (only when FULL_SPV is enabled)

### Healthy SPV sync
```
I/PeerGroup: Peer ... connected
I/BlockChain: Block connected: height=<N>
```

### Stuck SPV (PeerGroup not running)
```
# No PeerGroup logs after enabling FULL_SPV
```

Quick checks:

```bash
adb logcat | grep -i "PeerGroup"
adb logcat | grep -E "chainHead|bestHeight|Block connected"
```

---

## 4. Quick diagnosis commands

### Overlay-only (recommended)
```bash
adb logcat | grep -E "SNAPSHOT|SESSION_WALLET|DATA_SOURCE"
```

### FAST bootstrap lane (PoW sampling)
```bash
adb logcat | grep -E "\[FASTBOOT-[1-5]\]|FASTBOOT-STATE|FASTBOOT-COOLDOWN"
```

### FULL_SPV lane (if enabled)
```bash
adb logcat | grep -E "SPV|PeerGroup|BlockChain|chainHead"
```

---

## 5. ⛔ Things NOT to do

> [!CAUTION]
> These actions make things worse.

| Action | Why it's bad |
|--------|--------------|
| Clear app data / reinstall | Loses all canonical SPV progress and local overlay journals |
| Delete `*.spvchain` file | Forces SPV from genesis (if you later enable FULL_SPV) |
| Force-switch modes repeatedly | Makes debugging harder; overlay is safe but you’ll lose signal |
| Kill the app during a write | Can corrupt canonical blockstore (FULL_SPV only) |

---

## 6. When to actually worry

| Symptom | Action |
|--------|--------|
| App crashes repeatedly | Check logcat stack traces |
| `OverlappingFileLockException` | Multiple SPV instances; ensure strict guards |
| `UnreadableWalletException` | Wallet file corrupted; may require reset |
| Send fails repeatedly | Verify explorer availability + broadcast endpoint + fees |

---

🔚 End of Troubleshooting Guide
