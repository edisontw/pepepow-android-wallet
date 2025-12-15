# Debug Contract: FAST Bootstrap Logging

> [!NOTE]
> This document defines the **mandatory logging points** for FAST bootstrap debugging. All implementations must emit these logs consistently.

---

## 1. FASTBOOT_SESSION_ID

Every FAST bootstrap attempt is identified by a unique session ID:

### Format
```
FASTBOOT_SESSION_ID: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
```

### Lifecycle
- Generated at the **start** of each bootstrap attempt
- Logged in **every** FAST-related log message during that attempt
- Enables filtering all logs for a single bootstrap attempt

### Implementation
```java
private String fastBootSessionId = UUID.randomUUID().toString();
Log.i(TAG, "FASTBOOT_SESSION_ID: " + fastBootSessionId + " | Starting bootstrap");
```

---

## 2. Five Fixed Log Points

These five log events **MUST** be emitted during every FAST bootstrap:

### LogPoint 1: Bootstrap Start
```
[FASTBOOT-1] session=<ID> | state=RUNNING | Starting FAST bootstrap
```
**When**: Immediately when FAST bootstrap begins

---

### LogPoint 2: API Response
```
[FASTBOOT-2] session=<ID> | apiHeight=<N> | apiHash=<hash> | headers=<count>
```
**When**: After receiving headers from explorer API

---

### LogPoint 3: PoW Sampling Result
```
[FASTBOOT-3] session=<ID> | powSamples=10 | passed=<N> | failed=<N> | result=<PASS|FAIL>
```
**When**: After completing PoW spot-verification

---

### LogPoint 4: Bootstrap Result
```
[FASTBOOT-4] session=<ID> | result=<SUCCEEDED|FAILED> | reason=<reason> | newState=<state>
```
**When**: When bootstrap completes (success or failure)

---

### LogPoint 5: SPV Status
```
[FASTBOOT-5] session=<ID> | spvRunning=<true|false> | spvHeight=<N> | peerGroupActive=<true|false>
```
**When**: After bootstrap completes, confirming SPV state is unaffected

---

## 3. Additional Diagnostic Logs

These are optional but recommended for debugging:

### State Transitions
```
[FASTBOOT-STATE] session=<ID> | transition=<FROM>→<TO> | trigger=<reason>
```

### Blockstore Guard Violations
```
[FASTBOOT-VIOLATION] session=<ID> | operation=<op> | blocked=true | reason=<reason>
```

### Cooldown Status
```
[FASTBOOT-COOLDOWN] session=<ID> | cooldownExpires=<timestamp> | remainingMs=<N>
```

---

## 4. Log Filtering

To filter logs for a specific bootstrap attempt:

```bash
adb logcat | grep "FASTBOOT.*session=<SESSION_ID>"
```

To see all FAST-related logs:

```bash
adb logcat | grep -E "\[FASTBOOT-[1-5]\]|\[FASTBOOT-STATE\]|\[FASTBOOT-VIOLATION\]"
```

---

## 5. Example Complete Session

```
I/BlockchainService: [FASTBOOT-1] session=a1b2c3d4-... | state=RUNNING | Starting FAST bootstrap
I/ApiBootstrapper:   [FASTBOOT-2] session=a1b2c3d4-... | apiHeight=285432 | apiHash=00000abc... | headers=1000
I/PowSampler:        [FASTBOOT-3] session=a1b2c3d4-... | powSamples=10 | passed=10 | failed=0 | result=PASS
I/BlockchainService: [FASTBOOT-4] session=a1b2c3d4-... | result=SUCCEEDED | reason=pow_validated | newState=SUCCEEDED
I/BlockchainService: [FASTBOOT-5] session=a1b2c3d4-... | spvRunning=true | spvHeight=284500 | peerGroupActive=true
```

### Failed Session Example

```
I/BlockchainService: [FASTBOOT-1] session=e5f6g7h8-... | state=RUNNING | Starting FAST bootstrap
I/ApiBootstrapper:   [FASTBOOT-2] session=e5f6g7h8-... | apiHeight=285432 | apiHash=00000abc... | headers=1000
E/PowSampler:        [FASTBOOT-3] session=e5f6g7h8-... | powSamples=10 | passed=7 | failed=3 | result=FAIL
W/BlockchainService: [FASTBOOT-4] session=e5f6g7h8-... | result=FAILED | reason=pow_mismatch | newState=DISABLED_SESSION
I/BlockchainService: [FASTBOOT-5] session=e5f6g7h8-... | spvRunning=true | spvHeight=284500 | peerGroupActive=true
```

---

🔚 *End of Debug Contract*
