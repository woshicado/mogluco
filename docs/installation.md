# Installation

Mogluco is not on any app store, and **no prebuilt APK is distributed**: it relies on Juggluco's closed-source sensor libraries, which are not redistributable (see [below](#1-supply-the-native-libraries)). You build it from source — the steps below automate fetching those libraries, so it comes down to a couple of commands.

During installation, your phone might ask you to let Google scan the app; you can skip that scan.
The app might require special permissions for certain functionalities such as the **Always-on Display** widget, and unrestricted background usage so readings always come through and the OS does not stop the app. Mogluco asks for these when you press the corresponding buttons in Settings.

It runs on a phone with **Android 8.0 (API 26) or newer**, with an optional **Wear OS 3+** companion, and can be installed alongside the original Juggluco.

## Building from source

### Prerequisites

- **Android Studio** (or the Android SDK + command-line tools) with a **JDK 17+** — the JetBrains Runtime bundled with Android Studio works well.
- **Android SDK Platform 36** and the NDK / CMake (pulled automatically by Gradle for the native engine).
- **`mise`** (optional but recommended — it drives the build/install tasks) and a POSIX shell with `curl` + `unzip` for the library-fetch step.
- The **closed-source sensor libraries** (see below). They are not redistributable, so they are **not** included in this repository.

### 1. Supply the native libraries

Mogluco reuses Juggluco's sensor engine, which links against a few closed-source `.so` blobs (Libre calibration and Libre 3 crypto / activation code). These belong to Abbott / Juggluco and cannot be redistributed under the GPL, so — exactly as [upstream Juggluco's own build instructions](https://github.com/j-kaltes/Juggluco#build-juggluco) do — they are **extracted from an official Juggluco APK** rather than shipped here.

**The easy way** — a helper script downloads the right Juggluco APK(s) and drops every library into place:

```bash
mise run blobs          # or, without mise:
scripts/extract-blobs.sh
```

Run it once; it is a no-op afterwards. `mise run build` / `mise run ship` also run it automatically, so in practice you never call it by hand. If you already have an official Juggluco APK downloaded, point the script at it to skip the download:

```bash
scripts/extract-blobs.sh ~/Downloads/Juggluco-10.9.7.apk
```

**What it fetches, and why two APKs.** Four libraries are needed:

| Library | Destination | Source |
|---|---|---|
| `libcalibrat2.so` | `Common/src/main/jniLibs/<abi>/` | current Juggluco APK |
| `libinit.so`, `liblibre3extension.so` | `Common/src/libre3/jniLibs/<abi>/` | current Juggluco APK |
| `libcrl_dp.so` | `Common/src/libre3/jniLibs/arm64-v8a/` | Juggluco **10.6.1** APK |

`<abi>` is `arm64-v8a` for a modern phone/watch (the script also fills `armeabi-v7a` for a 32-bit Wear OS watch). `libcrl_dp.so` (the Libre 3 NFC-activation library) was folded into `liblibre3extension.so` in Juggluco after v10.6.x, so current APKs no longer ship it as a standalone file; Mogluco's engine still loads it separately, so the script pulls it from the last version that bundled it. Override either source with `JUGGLUCO_VERSION` / `JUGGLUCO_CRL_VERSION` if those downloads ever move.

**Doing it by hand** (if you prefer): unzip an official Juggluco APK from <https://www.juggluco.nl/Juggluco/download.html> and copy the files from `lib/<abi>/` into the matching `Common/src/**/jniLibs/<abi>/` folders above. A single multi-arch APK of **v10.6.1 or older** contains all four libraries.

!!! note
    These blobs are loaded at runtime and **must not be stripped**.
    The build already sets `useLegacyPackaging` and `doNotStrip` for them, and Gradle fails early with a pointer to this step if any are missing.
    Only the `cpp` and `jniLibs` parts of the legacy `Common` module are used; the rest of the original app's Java/resources have been removed.

### 2. (Optional) Signing config

For a release build, create `keystore.properties` in the repo root pointing at a keystore:

```properties
storeFile=release.keystore
storePassword=…
keyAlias=…
keyPassword=…
```

Or just build the **debug** variant, which is signed with the debug key and needs no setup.

### 3. Build

With `mise` (fetches the sensor libraries first if needed):

```bash
mise run build              # debug phone APK
mise run build --release    # release phone APK
```

Or straight through Gradle:

```bash
./gradlew :app:assembleRelease     # phone app
./gradlew :wear:assembleRelease    # Wear OS app
```

The APKs land in `app/build/outputs/apk/release/` and `wear/build/outputs/apk/release/`.

### 4. Install

Build and install to a connected phone and/or watch in one step:

```bash
mise run ship               # release-build both and install to phone + watch
mise run ship --phone       # just the phone
mise run push               # install the latest already-built phone APK
```

Or with `adb` directly:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
# and, on the watch:
adb install -r wear/build/outputs/apk/release/wear-release.apk
```

!!! tip "Watch pairing"
    The phone and watch apps use the Wear OS Data Layer, which requires them to share the **same application id and signing key**. Build and install both from the same checkout so they can talk to each other.

Next: **[Onboarding](onboarding.md)**.
