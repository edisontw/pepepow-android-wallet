# Troubleshooting: Sync Issues

This guide helps diagnose sync-related issues in the PEPEPOW wallet.

---

## 1. "Sync Appears Stuck"

### Check These Logs First

```bash
adb logcat | grep -E "FASTBOOT|SPV|PeerGroup|BlockChain"
```

### Differentiate: API Overlay vs SPV Slow

| Symptom | Likely Cause | Solution |
|---------|--------------|----------|
| UI height stuck, logs show `FASTBOOT-4 result=FAILED` | Overlay failed, SPV still syncing | Wait for SPV; no action needed |
| UI height stuck, logs show SPV height increasing | UI display bug | Check `onBlocksDownloaded` callback |
| UI height stuck, no SPV logs after `FASTBOOT-5` | PeerGroup not started | Check `peerGroup.start()` is called |
| UI height stuck, `peerGroupActive=false` in logs | PeerGroup stopped unexpectedly | Check for crashes, LLMQ NPEs |

### Key Log Patterns

**Healthy SPV sync:**
```
I/PeerGroup: Peer ... connected
I/BlockChain: Block connected: height=<N>
```

**Stuck sync (SPV not running):**
```
# No logs after FASTBOOT-5, no PeerGroup activity
```

**Overlay failed but SPV healthy:**
```
W/BlockchainService: [FASTBOOT-4] result=FAILED ...
I/BlockchainService: [FASTBOOT-5] spvRunning=true | peerGroupActive=true
I/PeerGroup: Peer ... connected
```

---

## 2. Quick Diagnosis Commands

### Check if PeerGroup is running:
```bash
adb logcat | grep -i "PeerGroup"
```

### Check SPV height changes:
```bash
adb logcat | grep -E "chainHead|height="
```

### Check for FAST failures:
```bash
adb logcat | grep "FASTBOOT-4.*FAILED"
```

### Check for LLMQ/Quorum errors:
```bash
adb logcat | grep -E "Quorum|LLMQ|NullPointer"
```

---

## 3. Common Misunderstandings

### ❌ "FAST failed, I need to reinstall"
**Reality**: FAST failure only disables the overlay. SPV continues normally. Just wait.

### ❌ "UI shows wrong height, sync is broken"
**Reality**: UI may show overlay height (API) vs SPV height. Check both values.

### ❌ "SPV is slow, I should clear blockstore"
**Reality**: Clearing blockstore forces SPV to restart from genesis. Never do this.

### ❌ "Switching to FULL_SPV will fix it"
**Reality**: FULL_SPV is always running. Switching modes only affects the overlay.

---

## 4. ⛔ Things NOT to Do

> [!CAUTION]
> These actions will make things **worse**, not better.

| Action | Why It's Bad |
|--------|--------------|
| Clear app data / reinstall | Loses all SPV progress, restarts from genesis |
| Delete `*.spvchain` file | Same as above |
| Force-switch sync mode mid-sync | Can cause state inconsistencies |
| Kill app during sync | May corrupt blockstore |
| Disable/re-enable network repeatedly | Disconnects peers, slows sync |

---

## 5. When to Actually Worry

These symptoms indicate real problems:

| Symptom | Action |
|---------|--------|
| App crashes repeatedly | Check logcat for stack traces |
| `NullPointerException` in `Quorum` | LLMQ not fully disabled; check `isLlmqEnabled()` |
| `OverlappingFileLockException` | Multiple SPV threads; check initialization guards |
| `UnreadableWalletException` | Wallet file corrupted; may need reset |
| No peers connecting for 10+ minutes | Network issue or wrong seeds |

---

## 6. Useful Logcat Filters

### All sync-related logs:
```bash
adb logcat | grep -E "FASTBOOT|SPV|PeerGroup|BlockChain|chainHead"
```

### Errors and warnings only:
```bash
adb logcat *:E | grep -E "wallet|spv|peer|block"
```

### Specific session:
```bash
adb logcat | grep "session=<YOUR_SESSION_ID>"
```

---

🔚 *End of Troubleshooting Guide*
