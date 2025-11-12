Welcome to _Dash Wallet_, a standalone Dash payment app for your Android device!

This project contains several sub-projects:

 * __wallet__:
     The Android app itself. This is probably what you're searching for.
 * __common__:
     Contains common components used by integrations.
 * __uphold-integration__
     Contains the uphold integration
 * __market__:
     App description and promo material for the Google Play app store.
 * __integration-android__:
     A tiny library for integrating Dash payments into your own Android app
     (e.g. donations, in-app purchases).
 * __sample-integration-android__:
     A minimal example app to demonstrate integration of digital payments into
     your Android app.

## Build requirements

This tree expects the Android command-line tools plus matching native toolchain versions to be present locally (they are **not** checked into git):

* Android SDK Platform 28 / Build-Tools 30.x.
* Android NDK r29 (`29.0.14206865`). Install it via the Android Studio SDK Manager or unpack it under `external/android-ndk-r29` and point `ndk.dir` at it.
* CMake 3.19.8 (side-by-side).

Create a `local.properties` at the repository root (kept out of git) with at least:

```
sdk.dir=C:\\Android\\Sdk
ndk.dir=C:\\Android\\Sdk\\ndk\\29.0.14206865
cmake.dir=C:\\Android\\Sdk\\cmake\\3.19.8
```

Adjust the paths for your platform. A clean clone with only the SDK/NDK/CMake installed as above can build without any additional prebuilts.

## Building

Use the included Gradle wrapper. The default validation path is:

```
./gradlew :wallet:assembleDebug
```

Release builds continue to work through:

```
./gradlew assembleProdRelease
```

APK artifacts are written under `wallet/build/outputs/apk`.


