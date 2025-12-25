# 🪙 PEPEPOW Wallet (Dash Wallet v7.0.0 Retarget)

## CHANGELOG.md

# Changelog

## [1.0.0] - 2025-12-26

### Added
- In-memory **Tx→UTXO Snapshot Session Wallet** for fast usability.
- Snapshot-based balance calculation independent of FULL_SPV sync.
- Local outgoing transaction journal (sent txs + spent outpoints).
- Persistence of locally generated change addresses for snapshot rescans.
- Explicit source routing between canonical SPV data and API session data.

### Changed
- FAST_API_10POW and API_1000POW are finalized as **non-canonical bootstrap overlays**.
- FULL_SPV is strictly isolated as the only canonical chain writer.
- PoW sampling lane is decoupled from snapshot usability; PoW failure no longer blocks Send.
- Wallet startup prioritizes UI usability without triggering SPV sync.

### Fixed
- Fixed PIN-related crashes and state loss during send flow.
- Corrected balance miscalculation involving change outputs.
- Fixed duplicate or phantom history entries (pending vs confirmed sent txs).
- Prevented false “incoming funds” notifications after outgoing transactions.
- Restored correct state after app restart (balance, history, sent records).
- Normalized explorer transaction links and PEPEPOW denomination display.

### Safety & Guarantees
- Overlays never modify blockstore, chainHead, rollback state, or wallet.dat.
- Overlay failure is strictly non-destructive and cannot break SPV.
- Inputs are locally locked immediately after transaction creation.

### Known Limitations
- Old wallet import / restore is not supported in this release.
- Snapshot relies on explorer transaction data; global spent index is unavailable.
- FULL_SPV must be started manually and is intentionally gated in UI.


## [1.0.0-beta] - 2025-11-28

### Added
- FAST_API_10POW accelerated sync mode.
- New API bootstrap: fetch tip, pull last 1000 headers, verify 10 PoW blocks.
- `SyncMode` enum (`FULL_SPV`, `FAST_API_10POW`, `API_1000POW`).
- Developer Options UI for selecting sync mode.
- API status display.

### Changed
- Rebuilt SPV bootstrap pipeline (blockstore → API → blockchain → peergroup).
- Updated API endpoints and parsing logic (difficulty, masternodes, price).
- Improved initialization order to prevent blockstore corruption.
- Increased robustness of difficulty verification.

### Fixed
- Resolved `UnreadableWalletException` caused by premature wallet load.
- Fixed `difficulty bits mismatch` after API-assisted bootstrap.
- Eliminated loading freeze due to early PeerGroup start.
- Repaired block list display and explorer link navigation.

### Removed
- Removed static checkpoint system.
- Removed hardcoded height-based checkpoint assumptions.

### Known Issues
- SPV may momentarily appear ahead by ~300 headers.
- API-based UTXO sync not yet implemented.
- SyncMode changes may require reinstall due to DI caching.

---

### Framework Refinement (2025-12)

#### Changed
- FAST_API_10POW is now a fast-bootstrap **overlay**; `FULL_SPV` is the only canonical chain writer
- Overlay data stored in memory-only variables, never written to blockstore

#### Fixed
- Prevent FAST from resetting blockstore/rollback/restarting PeerGroup
- FAST failure no longer affects SPV sync state

#### Added
- `FAST_BOOT_STATE` machine (IDLE/RUNNING/SUCCEEDED/DISABLED_SESSION/DISABLED_COOLDOWN)
- Session disable + cooldown on FAST failure
- Clear separation between overlay UI data and canonical SPV chain

---

### Key Updates

* **PEPEPOW retargeting**
* **Network Parameters**

  * Replaced Dash-specific seeds and budgeting defaults with PEPEPOW parameters.
  * Updated `NetworkParameters` / `AbstractBitcoinNetParams` for dual PoW limits and fork-height awareness.
  * Switched main/test/reg networks to new ports, checkpoints, seeds, and difficulty limits.
  * Added post-HF guard logic and temporary Java stub for `XelisV2.hash()`.
* **Resources & UX**

  * Adjusted app name, strings, and shortcuts to reflect PEPEPOW branding.
  * Removed Firebase dependencies and replaced the third-party indicator view with a minimal placeholder.
* **Build System**

  * Migrated Gradle build to use `dashj` v21 via `includeBuild`.
  * Synchronized dependency versions and cloned `bls-signatures` for native build.
  * Updated CMake configuration for NDK 29 and CMake 3.22.

### Native + Build updates (2025-11)

* Updated the Gradle wrapper to 6.5 + AGP 4.0.2, switched `wallet` to the `plugins {}` DSL, enabled Java 8 `compileOptions`, Kotlin `jvmTarget = 1.8`, and wired up `coreLibraryDesugaring` to silence desugar warnings.
* Vendorized `wallet/cpp/dashj-bls/bls-signatures` at upstream commit `581b761f5f6c9f8b975082d7336c371273db3556`, preserved upstream LICENSE/NOTICE, and patched `contrib/relic/src/md/blake2.h` to drop `#pragma pack` in favor of explicit padding for ARM alignment.
* Added `relic_stubs.c`, refreshed `bls-signatures.cmake`, `bls-signatures-src.cmake`, and the JNI `CMakeLists.txt` so we build against the vendored relic sources deterministically.
* Regenerated the SWIG wrapper which now uses `bls::Signature::Aggregate(...)` and tightened JNI exception paths.
* Standardized all protobuf usage on `com.google.protobuf:protobuf-java:3.4.0` and globally excluded `protobuf-javalite` to avoid duplicate lite/runtime issues.
* Buffered OkHttp direct payment uploads by caching the serialized `Payment` proto, fixing intermittent content-length issues in `DirectPaymentTask`.
* Documented the side-by-side NDK r29 and CMake 3.19.8 requirements in `README.md` so a clean clone can build `:wallet:assembleDebug` without host tooling leakage.
* NOTE: `XelisV2` remains a stub implementation until the finalized consensus hashing routine is available.

### Current Build Status

* Java/Kotlin components compile successfully.
* Native build (`:wallet:generateJsonModelBetaDebug`) fails during BLS compilation.
* Issue: Android Gradle Plugin rejects CMake 3.22.1's reported version string (`3.22.1-g37088a8`), throwing a NullPointerException before dependency resolution.
* APK not yet produced; all Java/Kotlin logic ready.

### Next Steps

1. Patch Android Gradle Plugin or wrapper to recognize CMake 3.22.x.
2. Re-run `./gradlew :wallet:assembleDebug` after patch.
3. Validate `XelisV2` hashing stub (pure Java or JNI) once consensus implementation stabilizes.
4. Run full test suite with `./gradlew test` and confirm APK boots using PEPEPOW params.
