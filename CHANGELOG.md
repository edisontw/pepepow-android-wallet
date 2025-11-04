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