# Third-party components

## Ruffle and Ruffle Android

- Ruffle: https://github.com/ruffle-rs/ruffle — MIT OR Apache-2.0.
- Android frontend: https://github.com/ruffle-rs/ruffle-android — MIT OR Apache-2.0.
- Android license text and original copyright notices: ruffle-android.md.
- Runtime license text: `third_party/ruffle/LICENSE.md` in the Ruffle submodule.

This project modifies the Android frontend to add Legend Online server selection, official portal login, FlashVars, touch and display behavior, and runtime integration. The Ruffle fork adds URLLoader cancellation, optional disk caching and frame snapshots, and XML memory improvements. These modifications are maintained in this repository and the `legend-online` branch of https://github.com/ldnovaes/legend-online-ruffle.

## Platform TLS verification

The Android integration includes rustls-platform-verifier:
https://github.com/rustls/rustls-platform-verifier (MIT OR Apache-2.0).
Its original license texts are preserved in `android/vendor/rustls-platform-verifier/`.

## Linux desktop dependencies

The desktop client uses PySide6/Qt and Wine installed separately on the user's system. Their respective licenses apply to those dependencies. The Linux source bundle does not redistribute Qt, Wine, Adobe Flash, or the reference Brov client.

## Game content

Legend Online / Wartune and its assets belong to their respective owners. The clients load game content from official services; the source repository and release bundles do not include the game assets or a proprietary Flash runtime.
