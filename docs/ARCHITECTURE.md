# Architecture

## Process model

NexusClone runs guest applications inside the host application's own processes. There is no separate app_process, no zygote fork of the guest, and no root-level process injection. The host process is `com.kiyoscripts.nexusclone`, started normally by the system from the launcher or from a service.

Guest components are declared in `Bcore/src/main/AndroidManifest.xml` as a fixed pool of proxy components. The process pool is `:p0` through `:p49`, declared as `android:process` values on stub activities, services and providers. When a guest activity needs to run, the container picks a free stub component from the pool, binds the guest's `ComponentName` to it in the container's component map, and launches the stub. The system therefore sees only host components; the guest's real class is instantiated inside the host process by the container's `ClassLoader`.

Guest code is loaded through a custom `ClassLoader` that is chained to the host's class loader. The guest's `classes.dex` files are extracted from the installed APK (and its splits) into the instance data directory, then loaded with a `DexClassLoader` whose parent is the host loader. Resource access goes through a merged `AssetManager` that adds the guest APK paths to the host's asset path list, so guest resources resolve without a separate `Resources` object per process. Component entry points are invoked reflectively after the container has rewritten the `Intent`, the `ActivityInfo` and the process name to the stub values.

The guard process is a second, lightweight process declared in the same manifest. It holds a bound AIDL connection to the host process and exists solely to observe host liveness and to attempt a restart when the host dies. It runs no guest code.

## Instance lifecycle

States:

```
CREATED
    the instance record exists, the APK has been copied into the instance
    directory, but no dex has been extracted and no component map entry exists.
    -> INSTALLED on successful dex extraction and package parse
    -> DEAD on install failure or on user cancel

INSTALLED
    the package is parsed, the component map is populated, the data directory
    tree exists, but no process has been started for this instance.
    -> COLD on first launch request
    -> DEAD on uninstall

COLD
    a launch has been requested and the container has allocated a stub
    component and a process slot, but the guest's Application object has not
    finished onCreate.
    -> WARM when the guest Application onCreate returns
    -> DEAD if the process dies during startup

WARM
    the guest Application is alive in a host process, no guest activity is
    resumed.
    -> FOREGROUND when a guest activity reaches onResume
    -> BACKGROUND when the last guest activity is stopped
    -> FROZEN when the container freezes the instance to reclaim resources
    -> DEAD when the process is killed

FOREGROUND
    at least one guest activity is resumed and visible.
    -> WARM when the last activity is paused and stopped without being destroyed
    -> BACKGROUND when the task is moved to the back
    -> DEAD when the process is killed

BACKGROUND
    the instance has live components but nothing is visible.
    -> FOREGROUND on task resume
    -> WARM when all activities are destroyed but the Application survives
    -> FROZEN when the keep-alive budget is exhausted or the user freezes it
    -> DEAD when the process is killed by the system or by the user

FROZEN
    the instance's processes are suspended by the container; state is retained
    in memory but no guest code runs.
    -> WARM on thaw
    -> DEAD on uninstall or on process death while frozen

DEAD
    terminal for this process generation. The instance record and data
    directory survive; the component map entry is released.
    -> COLD on the next launch request
    -> (removed) on uninstall
```

## Preview pipeline

Previews are captured with `PixelCopy.request` against the guest window's `SurfaceView` or the window's `Surface`, on two triggers: when the instance's top activity is paused, and on a periodic timer while the instance is in `FOREGROUND`. The pause trigger is the reliable one; the timer exists so that a long-running foreground instance does not show a stale image.

The captured `Bitmap` is downscaled to 180x320 with `Bitmap.createScaledBitmap` using filtering, then encoded to WebP at quality 80 and written to the instance's preview cache file. The full-resolution bitmap is recycled immediately; only the WebP is retained. The cache is keyed by `(userId, packageName)` and carries the package's version code and last-update time.

Invalidation happens on package replace: when an instance is reinstalled or its APK is updated, the cached preview is deleted and the instance shows a placeholder until the next capture. Uninstall deletes the cache entry along with the data directory. Capture failures (surface not yet available, `PixelCopy` error) are logged and leave the previous preview in place rather than clearing it.

## Keep-alive layers

The keep-alive stack is layered, and the layers differ substantially in what they actually survive.

- Foreground service with `specialUse` foreground service type. Survives normal backgrounding and ordinary low-memory pressure. Does not survive Doze's restricted state for network work, and does not survive a force-stop.
- Guard process holding a bound AIDL connection to the host. Survives host process death and can attempt a restart. Does not survive a force-stop, because a force-stop clears the package's alarms, jobs and bindings.
- `setProcessImportant` via reflection on `ActivityManager`. Raises the process's oom_adj while the call holds. Survives ordinary memory pressure. Does not survive Doze, and the reflection target is not stable across OEM framework builds.
- `AlarmManager` pulse every 9 minutes. Survives Doze only if the alarm is set with `setExactAndAllowWhileIdle` and the app holds the exemption; a plain repeating alarm is deferred to the maintenance window. Does not survive a force-stop.
- `JobScheduler` expedited job as a fallback when the alarm path is unavailable. Runs in Doze only within the expedited job quota; beyond the quota it is deferred. Does not survive a force-stop.
- Battery optimization exemption request. Once granted, removes the app from Doze app-standby restrictions. Does not survive a force-stop, and some OEMs ignore the exemption.
- Per-OEM autostart deep links. These open the vendor's own autostart settings screen so the user can whitelist the app. They are the only layer that helps against OEM cleanup, and they only help if the user completes the flow.

Summary: nothing in this stack survives a force-stop from the recents screen, and nothing survives an OEM background cleanup unless the vendor autostart permission has been granted. Doze is survivable only for the alarm and expedited-job paths, and only within their quotas.

## Identity injection

Identity values are injected at three levels.

Java layer: `Build` fields (`MANUFACTURER`, `BRAND`, `MODEL`, `DEVICE`, `PRODUCT`, `FINGERPRINT`, `SERIAL`, `ID`, `DISPLAY`, `HARDWARE`, `BOARD`, `BOOTLOADER`, `TAGS`, `TYPE`, `HOST`, `USER`) are replaced by rewriting the static final fields on the `android.os.Build` class in the guest's class loader, before any guest code runs. `Settings.Secure.ANDROID_ID` is intercepted in the `ContentResolver`/`Settings` call path and answered from the instance profile. `TelephonyManager.getDeviceId`, `getImei`, `getSubscriberId` and `getSimSerialNumber` are hooked to return the profile's IMEI. `WifiInfo.getMacAddress` and `NetworkInterface.getHardwareAddress` are hooked to return the profile's MAC.

Native layer: the `ro.product.*` system properties are spoofed by Dobby inline hooks in `Bcore/src/main/cpp/Utils/VirtualSpoof.cpp`. The hooks intercept the property read path so that `__system_property_get` and the `__system_property_find`/`read` pair return the instance's values for `ro.product.model`, `ro.product.brand`, `ro.product.manufacturer`, `ro.product.device`, `ro.product.name` and `ro.build.fingerprint`. This covers native libraries that read properties directly instead of going through the Java `Build` class.

The profile is stored per instance and is applied at process start, before the guest `Application` is created, so that early reads during static initialization see the spoofed values.

## Snapshot format

A `.vinstance` file is a POSIX tar archive. It contains the instance's data directory tree, preserving relative paths, plus a `manifest.json` at the archive root.

`manifest.json` fields:

- `packageName` — the guest package name.
- `userId` — the virtual user id the snapshot was taken from.
- `createdAt` — ISO 8601 timestamp of the snapshot.
- `entries` — an array of objects, one per archived file, each with the entry's relative path and the SHA-256 of its contents.

Restore reads the manifest first, verifies each entry's SHA-256 against the archive contents, and aborts on any mismatch before writing anything into the target instance directory. The `userId` in the manifest is advisory: on restore into a different instance the container rewrites the user id to the target's, since the data directory path is derived from it.
