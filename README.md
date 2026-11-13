# NexusClone

NexusClone is an Android app and game cloner built on a fork of NewBlackBox, which itself descends from VirtualApp. It is an in-process container: guest APKs are loaded into the host application's own process through a custom ClassLoader and a set of proxied components, so no root access and no separate installation of the guest app is required. Each clone is a virtual user with its own data directory under the container's virtual root, which means several copies of the same APK can run side by side with independent storage, independent device identity and independent network configuration. The application id is `com.kiyoscripts.nexusclone`.

## Features

- Unlimited instances per APK. The same package can be cloned any number of times; each clone is a distinct virtual user id.
- Per-instance data directory. Files, databases, shared preferences and the guest's external storage view are redirected into the instance's own directory tree.
- Live preview thumbnails via `PixelCopy`, captured from the guest window and cached as downscaled WebP images.
- Keep-alive foreground service with a separate guard process that holds a bound AIDL connection to the host.
- Per-instance device identity: IMEI, MAC address, ANDROID_ID and the `Build` fields (manufacturer, model, brand, device, fingerprint, serial) are configurable per clone.
- Per-instance proxy. HTTP and HTTPS traffic from the guest can be routed through a proxy configured for that instance only.
- Instance snapshot and restore to `.vinstance` archives, so a configured clone can be exported and re-imported.
- Macro record and replay: input events are recorded against a running instance and replayed later.
- Split APK import, including base plus configuration splits and additional split packages.
- Per-instance storage quota, enforced on the redirected data directory.
- Notification isolation: guest notifications are rewritten so they are attributed to the clone rather than colliding with the host or with other clones.
- Floating window mode for running an instance outside the main UI.
- Diagnostics screen reporting container state, hook status, process list and per-instance counters.

## Requirements

- Android 7.0 (API 24) minimum.
- Target API 34.
- `arm64-v8a` and `armeabi-v7a` ABIs. There is no x86 build.

## Build

The build is expected to run on GitHub Actions rather than on a developer workstation, because it needs a full Android SDK installation plus NDK `29.0.13846066` for the native hooks in `Bcore/src/main/cpp`. The CI workflow provisions those and runs the release assembly.

Local command, assuming the toolchain is already installed:

```
./gradlew :app:assembleRelease
```

Required toolchain versions: NDK `29.0.13846066`, platform `android-35`, build-tools `35.0.0`.

## Architecture

The repository is split into two Gradle modules. `app/` contains the user interface and the instance manager: instance list, creation and import flows, settings, previews, diagnostics. `Bcore/` is the container engine, a fork of VirtualApp with the native hooking code under `Bcore/src/main/cpp`. `app/` depends on `Bcore/`; `Bcore/` has no dependency on `app/`.

App-side packages:

- `core/instance` — instance records, the `(userId, packageName)` key, install and uninstall, data directory allocation.
- `core/preview` — `PixelCopy` capture, downscaling, WebP cache and invalidation.
- `core/keepalive` — foreground service, guard process client, alarm and job scheduling.
- `core/identity` — per-instance device identity profiles and their injection points.
- `core/net` — per-instance proxy configuration and the Java-layer proxy hooks.
- `core/backup` — `.vinstance` snapshot creation and restore.
- `core/macro` — input event recording and replay.
- `core/diag` — diagnostics collection and the diagnostics screen backing model.
- `ui` — activities, fragments, adapters and view models.

## Instance model

An instance is identified by the pair `(userId, packageName)`. BlackBox allocates a virtual user id for each clone, starting at 0 for the first clone of a package and incrementing for each additional one; the package name is the guest's own package name and is not rewritten. Because the user id is part of the key, the same package can be installed many times without collision.

Each clone gets its own data directory under the virtual root, derived from the user id and package name. All file system access from the guest is redirected there: the app's private directory, its `files`, `cache`, `databases` and `shared_prefs` subdirectories, and its view of external storage. Uninstalling an instance removes that directory; snapshotting it produces a `.vinstance` archive.

## Known limits

- The Java-layer proxy only covers `OkHttp` and `HttpURLConnection`. Traffic that goes through a native `connect()` call, or that resolves names through the native resolver, bypasses the proxy entirely.
- Keep-alive cannot survive a force-stop from the recents screen, and cannot survive an OEM background cleanup unless the user has granted the vendor's autostart permission. In both cases the process is killed by the system and nothing in the app can restart it.
- Epic hooking works only on API 29 and 30. On other API levels the container falls back to the normal hook path.
- There is no `/dev/kvm` on the build host, so no emulator-based runtime verification is performed. All verification of the container behaviour is static: compilation, manifest checks and code review.

## License

`Bcore` derives from NewBlackBox, which derives from VirtualApp. Both are GPLv3. This fork keeps that license; see `LICENSE` for the full text. Any distribution of a binary built from this repository is subject to the GPLv3 source availability requirements.
