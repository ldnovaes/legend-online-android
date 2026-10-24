# Legend Online for Android

A standalone Kotlin app that runs Legend Online using the native [Ruffle](https://ruffle.rs/) Flash emulator. No Wine or Adobe Flash plugin is required.

**Want to play? [Download the signed ARM64 APK](https://github.com/ldnovaes/legend-online-android/releases/download/v0.1.0-alpha.1/legend-online-0.1.0-alpha.1-android-arm64.apk).** The published APK requires Android 8.0+ and a 64-bit ARM phone or tablet. It is an early alpha, with landscape controls and the original desktop game interface.

## Install

1. Download the APK on your phone and open it.
2. Allow your browser or file manager to install unknown apps when Android asks.
3. Open Legend Online, choose your region and server, and sign in through the official login page.
4. Play in landscape mode. You need internet access and a game account. The first load downloads game resources.

The release APK supports ARM64 (`arm64-v8a`) only. A previous debug-signed development installation must be removed before installing the release-signed app; uninstalling removes its local data.

**Looking for the PC version? See [Legend Online for Linux](https://github.com/ldnovaes/legend-online-linux).** Windows and macOS installers are not provided.

This is an independent community client, not an official release from the game publisher. Game content is downloaded from official services and is not bundled in the APK.

## How it works

1. `LegendActivity` lists servers from the official portal, with search and recent servers.
2. Selecting a server opens the official login flow in a WebView if needed.
3. After login, the app obtains the server's `game.jsp` URL and constructs the `Loading.swf` URL and FlashVars.
4. `PlayerActivity` runs the SWF in the native Ruffle runtime. The game downloads its content and connects directly to the game server, including its TCP connection.

The frontend is adapted from [ruffle-android](https://github.com/ruffle-rs/ruffle-android); its MIT/Apache-2.0 notice is in [LICENSE.md](LICENSE.md). The runtime uses the `legend-online` branch of [our Ruffle fork](https://github.com/ldnovaes/legend-online-ruffle/tree/legend-online), pinned by the Git submodule. The fixes include URLLoader cancellation, optional HTTP caching, and XML memory improvements.

## Build from source

Requirements:

- JDK 17 or newer.
- Android SDK Platform 36 and NDK 29 (the development setup uses `29.0.14206865`).
- `rustup`; [`rust-toolchain.toml`](rust-toolchain.toml) pins Rust and the Android targets.
- `cargo-ndk`, installed with `cargo install cargo-ndk`.

From a terminal:

```bash
git clone --recurse-submodules https://github.com/ldnovaes/legend-online-android.git
cd legend-online-android
cat > local.properties <<EOF_LOCAL
sdk.dir=$HOME/Android/Sdk
ndk.dir=$HOME/Android/Sdk/ndk/29.0.14206865
ndkTargets=arm64-v8a
ABI_FILTERS=arm64-v8a
EOF_LOCAL
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`. For an x86_64 emulator, change both `ndkTargets` and `ABI_FILTERS` to `x86_64`. Build support for another ABI does not mean that device has been tested.

### Signed release builds

Keep the signing keystore outside the repository. Configure these environment variables in your local shell or build environment:

- `SIGNING_KEYSTORE`: absolute path to your release keystore.
- `SIGNING_STORE_PASSWORD`: keystore password.
- `SIGNING_KEY_ALIAS`: signing key alias.
- `SIGNING_KEY_PASSWORD`: key password.

Then run:

```bash
./gradlew :app:testDebugUnitTest :app:assembleRelease
```

Release APKs are written to `app/build/outputs/apk/release/`. Without the signing configuration, the release APK is unsigned and cannot be installed as-is. Preserve the same signing key for future updates. A locally signed build cannot replace the published APK unless it uses the same key.

The inherited build integration skips native compilation when `GITHUB_ACTIONS` is set; in that environment, native libraries must be built separately and placed in `app/src/main/jniLibs/<ABI>/` before Gradle packages the app. Ordinary local builds compile the native runtime through `cargo-ndk`.

### Toolchain note

The current AGP 8.13.2 lint analyzer reports Kotlin 2.4 metadata compatibility messages during release analysis. The release build and JVM tests complete successfully, but this version is not claimed to be lint-clean.

## Validation and limitations

The automated JVM tests cover server parsing and construction of game launch URLs. Loading and rendering have been exercised during development, but this is not a guarantee that every game feature or device works. Performance and memory consumption vary by device, and the game still uses its desktop interface.

On-device gameplay validation requires access to the official services and a game account. The APK does not contain game assets or account credentials.

## Related repositories

- [Linux PC client](https://github.com/ldnovaes/legend-online-linux)
- [Patched Ruffle runtime](https://github.com/ldnovaes/legend-online-ruffle)

Report bugs in [Issues](https://github.com/ldnovaes/legend-online-android/issues) with your device, Android version, app version, and reproduction steps. Remove account details and session tokens from logs.

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for component credits.
