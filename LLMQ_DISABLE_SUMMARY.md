# LLMQ/Evolution Features Disabled for PEPEPOW

> *This is a dashj compatibility fix unrelated to the FAST bootstrap overlay. It addresses NPE crashes from Dash Evolution features that PEPEPOW does not support.*

## 🎯 Objective

Disable Dash Evolution (LLMQ, InstantSend, Quorum) features completely in PEPEPOW to prevent `NullPointerException` errors during SPV sync.

## ❌ Problem

PEPEPOW does not support Dash Evolution features, but bitcoinj/dashj code was still trying to access quorum parameters, causing crashes:

```
java.lang.NullPointerException: Attempt to invoke virtual method
'int org.bitcoinj.quorums.LLMQParameters.getDkgMiningWindowEnd()' on a null object reference
  at org.bitcoinj.evolution.AbstractQuorumState$1.notifyNewBestBlock(...)
```

This occurred during SPV sync in the `newBestBlockListener` when processing filtered blocks from peers.

## ✅ Solution Implemented

We implemented **Option A** from the requirements: Added a global boolean flag to check if LLMQ is enabled.

### Changes Made

#### 1. **NetworkParameters.java** - Added `isLlmqEnabled()` Method

**File:** `external/dashj/core/src/main/java/org/bitcoinj/core/NetworkParameters.java`

Added a new method that networks can override to disable LLMQ:

```java
/**
 * Returns whether LLMQ/Evolution features are enabled for this network.
 * Networks like PEPEPOW that don't support Dash Evolution features should override this to return false.
 * This prevents NPEs when quorum parameters are null or LLMQ_NONE.
 */
public boolean isLlmqEnabled() {
    return llmqChainLocks != null && llmqChainLocks != LLMQParameters.LLMQType.LLMQ_NONE;
}
```

**Default behavior:** Returns `true` if `llmqChainLocks` is set  and not `LLMQ_NONE`.

#### 2. **MainNetParams.java** - Override to Disable LLMQ for PEPEPOW

**File:** `external/dashj/core/src/main/java/org/bitcoinj/params/MainNetParams.java`

Overrode the method to return `false` for PEPEPOW:

```java
/**
 * PEPEPOW does not support LLMQ/Evolution features.
 * Returning false prevents NPEs in quorum logic.
 */
@Override
public boolean isLlmqEnabled() {
    return false;
}
```

**Note:** PEPEPOW already had LLMQ types set to `LLMQ_NONE` (lines 158-163), but that wasn't enough to prevent the NPE.

#### 3. **AbstractQuorumState.java** - Guard LLMQ Logic

**File:** `external/dashj/core/src/main/java/org/bitcoinj/evolution/AbstractQuorumState.java`

Added check before accessing quorum parameters in the `newBestBlockListener`:

```java
// Only check quorum rotation if LLMQ is enabled on this network
if (params.isLlmqEnabled() && AbstractQuorumState.this instanceof QuorumRotationState) {
    LLMQParameters.LLMQType llmqType = params.getLlmqDIP0024InstantSend();
    if (llmqType != null && params.getLlmqs().containsKey(llmqType)) {
        if (block.getHeight() % params.getLlmqs().get(llmqType).getDkgMiningWindowEnd() != 0) {
            shouldProcess = false;
        }
    }
}
```

**Line changed:** Line 604 in `AbstractQuorumState.java`

## 🧪 Expected Behavior After Fix

1. ✅ Bootstrap builds 1000 contiguous headers in BlockStore
2. ✅ BlockChain reinitializes with correct chain head
3. ✅ PeerGroup connects to peers
4. ✅ SPV begins receiving filtered blocks
5. ✅ **No LLMQ exceptions occur** (this was the main issue)
6. ✅ SPV current height starts increasing normally
7. ✅ Sync progress shows percentage
8. ✅ Blocks tab displays real SPV blocks
9. ✅ Wallet becomes usable quickly

## 🔍 Testing Recommendations

After the build completes:

1. **Clean Install:** Uninstall existing app and do a fresh install
2. **Monitor Logs:** Check for `AbstractQuorumState` and `NullPointerException` entries
3. **Verify SPV Sync:** Ensure SPV height progresses beyond "Unknown"
4. **Check Blocks Tab:** Confirm real blocks appear (not just genesis)
5. **Test Transactions:** Send/receive to confirm wallet functionality

## 📝 Files Modified

1. `external/dashj/core/src/main/java/org/bitcoinj/core/NetworkParameters.java`
   - Added `isLlmqEnabled()` method (line ~892)

2. `external/dashj/core/src/main/java/org/bitcoinj/params/MainNetParams.java`
   - Overrode `isLlmqEnabled()` to return `false` (line ~188)

3. `external/dashj/core/src/main/java/org/bitcoinj/evolution/AbstractQuorumState.java`
   - Added `params.isLlmqEnabled()` guard (line ~604)

## 🚀 Alternative Approaches NOT Used

We chose Option A, but here were the alternatives considered:

- **Option B:** Replace quorum listeners with no-op implementations
- **Option C:** Null-safe all Evolution code individually

Option A was chosen because it's:
- Cleaner and more maintainable
- Centralized control via a single flag
- Easy to understand and debug
- Follows OOP principles (override in subclass)

---

**Implementation Date:** 2025-11-25  
**Build Status:** Compiling...
