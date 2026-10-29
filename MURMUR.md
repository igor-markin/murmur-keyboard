# Murmur

Personal build of Gboard patches based on [PixelBoard](https://github.com/Akshayykadam/PixelBoard) (GPL-3.0).
Patches only — no prebuilt APKs, no signing keys in the repository.

## Differences from PixelBoard

- Package `dev.markin.murmur`, app name Murmur; installs next to stock Gboard.
- Signed with a local key (`~/.murmur/murmur.keystore`, password from `MURMUR_KEYSTORE_PASSWORD`);
  the leaked PixelBoard release key and the prebuilt `.mpp` bundles are removed.
- Signature bypass only answers "trusted" for Gboard's check of its own package; other
  callers keep the stock Google-certificate check. Gboard's WebDebugBridge provider is not exported.
- No self-updater and no `REQUEST_INSTALL_PACKAGES`.
- AI writing tools default to on-device AICore (Gemini Nano); the server host is left to
  Gboard's production config instead of the staging sandbox host.
- Super Insert (contacts / browsing history) and Emogen are off; Signboard stays off.
- Clipboard history retention is capped at 7 days.
- Gboard 18.4.6.985164140 added to the supported versions.

## Build

Requirements: JDK 21, Android SDK (build-tools 35, platform 35), a GitHub classic token with
`read:packages` in `~/.gradle/gradle.properties` (`gpr.user`, `gpr.key`) for the Morphe plugin.

```
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./build.sh input/gboard.apk
```
