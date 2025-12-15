# **PEPEPOW Wallet (Updated Overview)**

A standalone PEPEW payment app for Android, featuring fast-sync technology, updated consensus support, and improved mobile wallet performance.

This project contains several sub-projects:

* **wallet** – The Android application.
* **common** – Components shared across modules.
* **uphold-integration** – (Legacy) Uphold support.
* **market** – Play Store metadata & promotional content.
* **integration-android** – A small library for integrating PEPEW payments into Android apps.
* **sample-integration-android** – Example of PEPEW integration.

---

## **Recent Updates (2025)**

### ✅ **Sync Modes & Fast Bootstrap Overlay**

The wallet supports three sync configurations:

| Mode | Description |
|------|-------------|
| `FULL_SPV` | Full P2P SPV sync — the **only** canonical chain writer |
| `API_1000POW` | API-assisted bootstrap with 1000-header validation |
| `FAST_API_10POW` | **UI Overlay** — fast display snapshot, does NOT write to blockstore |

#### Security Principles

* **Canonical Chain**: Only `FULL_SPV` can write to blockstore, update chainHead, or perform rollback operations
* **Overlay Data**: `FAST_API_10POW` provides UI-only height/balance/transaction snapshots for fast display
* **Independence**: `FULL_SPV` always runs in background, regardless of overlay success/failure

#### Failure Behavior

* **FAST failure**: Disables overlay for current session + cooldown period; SPV continues normally
* **FAST success**: Overlay data shown in UI; SPV still syncs independently in background

---

### ✅ **XelisV2 Consensus Support Included**

The wallet now supports the **pure-Java XelisV2 PoW hash**, matching current PePe-core behavior:

* Activated automatically when block version triggers post-Xelis rules
* JNI hooks included for future native optimization
* Test vectors partially implemented

Full JNI/native XelisV2 is planned for a performance boost.

---

### 🔧 **Ongoing Work**

These items are implemented at code level but require final QA:

#### **UI Improvements / Bug Fixes (In Progress)**

* Missing icons in PIN pad & main menu
* API status panel not always visible
* Network monitor display not updating
* Developer Options visibility fixes

#### **Wallet Operations Pending Final Testing**

* Actual on-chain transfer (send/receive)
* Wallet import / export
* QR code + deep link behavior
* Multi-session SPV sync reliability

These will be validated once FAST_API_10POW is fully stabilized.

---

## **Build requirements**

(unchanged from original)

* Android SDK Platform 28 / Build-Tools 30.x
* Android NDK r29
* CMake 3.19.8
* Java 11

Include a `local.properties`:

```
sdk.dir=C:\\Android\\Sdk
ndk.dir=C:\\Android\\Sdk\\ndk\\29.0.14206865
cmake.dir=C:\\Android\\Sdk\\cmake\\3.19.8
```

---

## **Building**

Debug (testnet):

```
./gradlew :wallet:assembleDebug
```

Production (mainnet):

```
./gradlew :wallet:assembleProdDebug
```

APK outputs are under `wallet/build/outputs/apk`.

---

## **Current Development Status**

(Updated summary, merging original content + new changes)

* Native dependencies bundled (dashj, bls-signatures) at BLS commit `581b761…`.
* **FAST_API_10POW** fast bootstrap overlay for instant UI display (overlay only, not canonical sync).
* **XelisV2** pure-Java hashing active for post-Xelis blocks; JNI path prepared.
* Builds reproducible via Gradle 6.5 + AGP 4.0.2 + Java 11.
* Remaining work:

  * Native XelisV2 implementation
  * Complete UI polish
  * Fix display bugs in API/Network panels
  * Final QA: send/receive, wallet import/export, SPV multi-session sync
  * Gradle/AGP modernization after dependency updates

---

# **End of Updated README.md**