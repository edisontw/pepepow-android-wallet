# Project Cleanup Plan

This document outlines the files and directories identified for cleanup to prepare the **PepePoW Android Wallet** for a GitHub release.

> [!IMPORTANT]
> **No files have been deleted yet.** This is a proposal for your review.

## 1. Safe-to-Delete Items
These items are temporary build artifacts, logs, or caches that can be safely removed to clean the project.

### Build Artifacts & Caches
- **`.gradle/`** (Gradle cache directory)
- **`build/`** (Root build output)
- **`wallet/build/`** (Wallet module build output)
- **`common/build/`** (Common module build output)
- **`common/assets/build/`** (Assets build output, if present)
- **`external/dashj/build/`** (DashJ build output)
- **`external/dashj/core/build/`** (DashJ Core build output)
- **`out/`** (IDE output directory)
- **`tmp/`** (Temporary directory, contains ~1300 files)

### Log Files (Root Directory)
These are logs from previous build attempts and can be removed.
- `assemble-output.log`
- `assembleProdDebug.log`
- `build.log`
- `build_log*.txt` (Multiple variants: `build_log.txt`, `build_log_2.txt`, `build_log_clean_retry.txt`, `build_log_final.txt`, `build_log_fix.txt`, `build_log_info.txt`, `build_log_jdk11.txt`, `build_log_prod.txt`, `build_log_retry*.txt`, `build_log_revert.txt`)
- `build_output*.txt`
- `cmake-info.log`
- `dashj_build_log*.txt` (Multiple variants)
- `full_build_log*.txt`
- `gradle-help.log`
- `installDebug.log`
- `installProdDebug*.log`
- `sdkcmake.log`

## 2. Files to REMAIN (Do Not Delete)
These files are critical for the project and must be preserved.

### Core Modules
- **`wallet/`** (Source code, resources, assets)
- **`common/`** (Shared code)
- **`external/dashj/`** (DashJ source code)
- **`external/PePe-core/`**
- **`external/android-ndk*/`**
- **`external/cmake*/`**
- **`external/jdk*/`**

### Configuration & Metadata
- **`build.gradle`** (Root and module-level)
- **`settings.gradle`**
- **`gradle.properties`**
- **`local.properties`** (Keep for local dev, but usually not committed to release)
- **`AndroidManifest.xml`** (In `wallet`, `common`)
- **`.gitignore`**
- **`README.md`**, **`CHANGELOG.md`**, **`AUTHORS`**, **`COPYING`**

### Resources
- **`wallet/src/main/assets/`** (Wallet assets)
- **`wallet/res/`** (Layouts, drawables, values)
- **`common/res/`**

## 3. Warnings & Suspicious Items
Please review these items specifically:

- **`external/checkpoints.txt`**: This file is only **22 bytes**. This is unusually small for a checkpoints file. Please verify if this is intended or if it should be replaced with a valid checkpoints file before release.
- **`BUILD_FIX_NOTES.md`** & **`LLMQ_DISABLE_SUMMARY.md`**: These appear to be developer notes. They are safe to keep but you may want to exclude them from the final release package if they are for internal use only.

## 4. Summary Plan
1.  **Delete** all items listed in section 1.
2.  **Verify** `external/checkpoints.txt`.
3.  **Keep** all items in section 2.
4.  **Proceed** with release packaging.

---
**Action Required:**
Please confirm if you would like me to proceed with deleting the items in **Section 1**.
