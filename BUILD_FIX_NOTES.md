## BUILD_FIX_NOTES.md

### Native Build Issue Summary

* **Location:** `:wallet:generateJsonModelBetaDebug`
* **Root Cause:** CMake toolchain mismatch between Dash's BLS build scripts and Android Gradle Plugin.
* **Symptoms:** NPE triggered by version parsing of `3.22.1-g37088a8`.

### Suggested Fixes

#### Option A: Patch Gradle Wrapper / Android Plugin

* Edit `gradle.properties`:

  ```properties
  android.overridePathCheck=true
  cmake.dir=C:\\Users\\yench\\AppData\\Local\\Android\\Sdk\\cmake\\3.22.1
  ```
* Or add Gradle property override in `local.properties` for custom CMake path.
* If plugin hard-rejects version suffix, patch its `Version.java` parser or rename cmake folder to `3.22.1`.

#### Option B: Bundle a Compatible CMake Binary

* Download the canonical `cmake-3.22.1-windows-x86_64` package.
* Place it under `external/cmake` and point the NDK build system to that path using `CMakeLists.txt` `CMAKE_MAKE_PROGRAM` hint.

#### Option C: Upgrade Android Gradle Plugin

* Upgrade AGP in root `build.gradle` to a 4.x+ branch that supports modern CMake (requires Gradle ≥6.5).
* If doing this, ensure compatibility with Gradle wrapper (update to 6.5–7.0 range).

### Verification Checklist

* [ ] Confirm CMake reports as `3.22.x` without `-gXXXX` suffix in AGP logs.
* [ ] Run `./gradlew clean :wallet:assembleDebug`.
* [ ] Verify that `.apk` is generated under `wallet/build/outputs/apk`.
* [ ] Ensure no Firebase dependency or plugin warnings remain.
* [ ] Confirm PEPEPOW URI scheme (`pepew:`) is registered and working.

### Post-Build Tasks

* Integrate JNI or Java Xelis hashing implementation.
* Re-run regression suite under `de.schildbach.wallet_test`.
* Update `BUILD.md` with final toolchain versions (AGP, Gradle, NDK, CMake, dashj).

---

**Maintainers:**
Edison Huang @edisontw
Foztor (Core Integration)
PEPEPOW Dev Group 2025