# Debug Contract: FAST Overlay + Session Wallet Logging

This document defines the **mandatory logging points** for the bootstrap overlays.
The goal is to make overlay failures **observable and safe**, and to prove overlays **never break SPV**.

> [!IMPORTANT]
> There are **two independent lanes**:
> - **Lane A: PoW sampling** (optional trust signal)
> - **Lane B: Tx→UTXO Snapshot → Session Wallet** (required for fast usability)
>
> Snapshot lane success alone must be enough to enable Send.

---

## FASTBOOT_SESSION_ID

Each overlay attempt uses a process-lifetime unique session id:

```
FASTBOOT_SESSION_ID: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
```

It MUST appear in every log line for:
- PoW sampling lane
- Snapshot / Session Wallet lane
- UI source switching

---

## Mandatory log points (exact)

### 1) Bootstrap entry
```
[FASTBOOT-1] session=<ID> | mode=<MODE> | powState=<STATE> | snapshotState=<STATE> | lastRunTime=<ms>
```

### 2) State transitions (both lanes)
```
[FASTBOOT-STATE] session=<ID> | lane=<POW|SNAPSHOT> | transition=<FROM>→<TO> | trigger=<reason>
```

### 3) SPV lifecycle (must prove overlays do not touch it)
```
[SPV] session=<ID> | event=<START|STOP> | peerGroupActive=<true|false>
```

### 4) SPV chain progress (only when SPV is actually enabled/running)
```
[SPV-HEIGHT] session=<ID> | chainHead=<N> | bestHeight=<N>
```

### 5) UI source router switch
```
[DATA_SOURCE] session=<ID> | switch=<SPV_CANONICAL↔API_SESSION> | reason=<snapshot_ready|spv_caught_up|...>
```

---

## Exception logging (no silent catch)

Any caught exception in overlay code must log:

- exception class
- message
- lane (POW / SNAPSHOT)

Example:
```
W/BlockchainService: [FASTBOOT-ERR] session=<ID> | lane=SNAPSHOT | ex=SocketTimeoutException | msg=timeout
```

---

## Recommended logcat filters

```bash
adb logcat | grep -E "FASTBOOT|SNAPSHOT|SESSION_WALLET|DATA_SOURCE|\[SPV\]|\[SPV-HEIGHT\]"
```

🔚 End of Debug Contract
