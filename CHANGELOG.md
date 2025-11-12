# 🪙 PEPEPOW Wallet (Dash Wallet v7.0.0 Retarget)

## CHANGELOG.md

### Key Updates

* **PEPEPOW retargeting**

  * Renamed package to `org.pepepow.wallet` and rewired all application IDs.
  * Updated URIs, explorer links, constants, and introduced a minimal `CoinDefinition` for PEPEPOW (ticker, MIME types, explorer endpoints, and URI scheme).
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
