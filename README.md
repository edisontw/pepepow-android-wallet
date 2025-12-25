# PEPEPOW Android Wallet

A standalone **PEPEPOW (PEPEW)** payment wallet for Android, based on the Dash / bitcoinj SPV wallet codebase.

This repository contains several sub-projects:

- **wallet** – The Android application.
- **common** – Components shared across modules.
- **uphold-integration** – (Legacy) Uphold support.
- **market** – Play Store metadata & promotional content.
- **integration-android** – A small library for integrating PEPEW payments into Android apps.
- **sample-integration-android** – Example of PEPEW integration.

---

## 2025 Release Notes (Fast Usability Overlay)

### Sync / Bootstrap configurations

The wallet supports three configurations:

| Mode | What it is | Persistence / authority |
|------|------------|------------------------|
| `FULL_SPV` | Full P2P SPV sync | **Canonical** (writes blockstore & wallet.dat) |
| `API_1000POW` | API-assisted bootstrap overlay | **Overlay only** (never writes canonical state) |
| `FAST_API_10POW` | Fast usability overlay (Tx→UTXO snapshot) | **Overlay only** (never writes canonical state) |

> [!IMPORTANT]
> **Overlays are NOT sync modes.** They must never write to blockstore, modify chainHead, rollback, or touch `wallet.dat`.
>
> **This release defaults to overlay usability.** `FULL_SPV` can be exposed behind a developer / advanced toggle and must not auto-run or auto-switch.

### What FAST overlay does

`FAST_API_10POW` aims to make the wallet usable immediately after creation:

- Fetches address transaction lists via explorer API
- Builds a **Tx→UTXO snapshot** for wallet-owned addresses (birth-time scoped)
- Creates an **in-memory Session Wallet** for:
  - balance display
  - transaction list (incoming + locally-recorded outgoing)
  - send enablement + building/signing/broadcasting transactions
- Keeps a **local spent journal** for outgoing transactions (no global spent index available)

### What FAST overlay must NEVER do

- Delete or recreate blockstore files
- Start/stop/restart PeerGroup
- Update SPV chainHead, rollback, or write headers
- Persist any “session wallet” state as canonical
- Modify `wallet.dat` (except normal keychain usage for signing)

---

## Consensus (XelisV2)

The wallet includes **pure-Java XelisV2 PoW hashing** matching PePe-core post-Xelis rules, with JNI hooks prepared for future native optimization.

---

## Build requirements

Pinned toolchain for reproducible builds:

- Android SDK Platform 28 / Build-Tools 30.x
- Android NDK r29
- CMake 3.19.8 (or project-noted compatible version)
- Java 11
- Gradle 6.5 + AGP 4.0.2 (do not upgrade in this release)

Include a `local.properties`:

```properties
sdk.dir=C:\Android\Sdk
ndk.dir=C:\Android\Sdk\ndk\29.0.14206865
cmake.dir=C:\Android\Sdk\cmake\3.19.8
```

---

## Building

Debug (testnet):

```bash
./gradlew :wallet:assembleDebug
```

Production (mainnet):

```bash
./gradlew :wallet:assembleProdDebug
```

APK outputs are under `wallet/build/outputs/apk`.

---

## Documentation

- `FAST_BOOT_OVERLAY.md` – Overlay safety contract + state machines
- `FAST_API_10POW.md` – FAST overlay workflow & threat model
- `DEBUG_CONTRACT_FASTBOOT.md` – Mandatory logging points
- `TROUBLESHOOTING_SYNC.md` – Debugging overlay vs SPV issues
- `BUILD_FIX_NOTES.md` – Common build fixes / toolchain notes
