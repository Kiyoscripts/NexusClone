package com.kiyoscripts.nexusclone.core.diag

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.kiyoscripts.nexusclone.core.identity.IdentityFactory
import com.kiyoscripts.nexusclone.core.instance.CloneInstance
import com.kiyoscripts.nexusclone.core.instance.InstanceManager
import com.kiyoscripts.nexusclone.core.keepalive.KeepAliveController
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.core.env.BEnvironment
import top.niunaijun.blackbox.core.system.ServiceManager
import java.io.File

/**
 * How much a failing check actually matters.
 *
 * BLOCKER means clones cannot work at all, DEGRADED means they work but will misbehave,
 * INFO means the row is context and a failure is not a problem.
 */
enum class CheckSeverity { BLOCKER, DEGRADED, INFO }

/**
 * The one-tap fix for a failing check, if there is one.
 *
 * NONE means the row is not actionable and must not be clickable.
 */
enum class CheckRemedy { NONE, BATTERY_EXEMPTION, KEEPALIVE_SETTINGS, OVERLAY_PERMISSION }

/**
 * One diagnostic check with a pass/fail and a human-readable detail line.
 *
 * severity and remedy are defaulted so existing construction sites keep compiling.
 */
data class Check(
    val id: String,
    val title: String,
    val ok: Boolean,
    val detail: String,
    val severity: CheckSeverity = CheckSeverity.INFO,
    val remedy: CheckRemedy = CheckRemedy.NONE
)

/**
 * Runtime self-check.
 *
 * The point is to answer "why did my clone not work" without adb. Every check is
 * something that has actually broken in this class of app: engine services not bound,
 * virtual root unwritable, ABI mismatch, quota exceeded, keep-alive degraded.
 */
class Diagnostics(private val context: Context) {

    companion object {
        private const val TAG = "Diagnostics"
    }

    private val manager = InstanceManager.get(context)

    /**
     * Last sweep, reused for a short window.
     *
     * onResume fires on every rotation and on return from a system dialog, and a full sweep
     * costs a binder round-trip plus a provider retry loop. Twenty seconds is long enough to
     * absorb those, short enough that a manual re-run is never stale.
     */
    @Volatile
    private var cached: List<Check> = emptyList()

    @Volatile
    private var cachedAt = 0L

    /**
     * Run every check.
     *
     * Not cheap: the engine probe is a binder round-trip and the keep-alive check reaches
     * the provider client retry loop, which can sleep for up to 2 s. Call this from a
     * background dispatcher, never from the main thread.
     *
     * Pass force = true to bypass the short-lived cache, which is what the manual buttons do.
     */
    fun runAll(force: Boolean = false): List<Check> {
        val snapshot = cached
        if (!force && snapshot.isNotEmpty() && System.currentTimeMillis() - cachedAt < 20_000L) {
            return snapshot
        }
        val fresh = buildList {
            add(engineServices())
            add(virtualRoot())
            add(abiSupport())
            add(keepAlive())
            add(batteryExemption())
            add(storage())
            add(identityFactory())
            add(instanceHealth())
            add(gmsStatus())
            add(proxyStatus())
            add(processList())
            add(hookStatus())
        }
        cached = fresh
        cachedAt = System.currentTimeMillis()
        return fresh
    }

    private fun engineServices(): Check {
        // Probe a real binder rather than trusting areServicesAvailable(). That method used
        // to answer true unconditionally (every branch ended in `return true`, including the
        // fallback loop whose only producer always returns null), so this row reported PASS
        // on devices where zero services were bound, which is exactly the misdirection this
        // screen exists to prevent.
        //
        // getService() is a binder round-trip into the :black process and can block on a
        // provider timeout, so runAll() must stay off the main thread.
        val binder = try {
            BlackBoxCore.get().getService(ServiceManager.ACTIVITY_MANAGER)
        } catch (e: Exception) {
            Log.w(TAG, "getService(${ServiceManager.ACTIVITY_MANAGER}) threw: ${e.message}")
            null
        }
        val ok = binder != null
        return Check(
            "engine_services",
            "Container services bound",
            ok,
            if (ok) "Probed ${ServiceManager.ACTIVITY_MANAGER}: binder bound"
            else "Probed ${ServiceManager.ACTIVITY_MANAGER}: no binder; clones cannot launch",
            CheckSeverity.BLOCKER,
            CheckRemedy.NONE
        )
    }

    private fun virtualRoot(): Check {
        val root = try {
            BEnvironment.getVirtualRoot()
        } catch (e: Exception) {
            return Check(
                "virtual_root",
                "Virtual root writable",
                false,
                "unreadable: ${e.message}",
                CheckSeverity.BLOCKER,
                CheckRemedy.NONE
            )
        }
        val probe = File(root, ".nexus_probe")
        val ok = try {
            root.mkdirs()
            probe.writeText("ok")
            val read = probe.readText() == "ok"
            probe.delete()
            read
        } catch (e: Exception) {
            false
        }
        return Check(
            "virtual_root",
            "Virtual root writable",
            ok,
            if (ok) root.absolutePath else "cannot write to ${root.absolutePath}",
            CheckSeverity.BLOCKER,
            CheckRemedy.NONE
        )
    }

    private fun abiSupport(): Check {
        val abis = Build.SUPPORTED_ABIS.toList()
        val ok = abis.any { it == "arm64-v8a" || it == "armeabi-v7a" }
        return Check(
            "abi",
            "Supported ABI",
            ok,
            abis.joinToString(", ").ifEmpty { "unknown" },
            CheckSeverity.BLOCKER,
            CheckRemedy.NONE
        )
    }

    private fun keepAlive(): Check {
        val controller = KeepAliveController.get(context)
        // strength() returns 0 while the preference is off, and the preference defaults to
        // off, so requiring strength() >= 3 unconditionally failed this row on every fresh
        // install and pinned the summary to "needs attention" forever. Off is a choice, not
        // a fault: report it as degraded and let the user decide.
        if (!controller.isEnabled()) {
            return Check(
                "keepalive",
                "Keep-alive strength",
                true,
                "Disabled in Settings",
                CheckSeverity.DEGRADED,
                CheckRemedy.KEEPALIVE_SETTINGS
            )
        }
        val level = controller.strength()
        val max = controller.maxStrength()
        return Check(
            "keepalive",
            "Keep-alive strength",
            level >= 3,
            when {
                level == 0 -> "Disabled in Settings; no resurrection layer is armed"
                level >= max -> "$level of $max layers armed (service, exemption, alarm, job)"
                else -> "$level of $max layers armed; clones may be killed sooner"
            },
            CheckSeverity.DEGRADED,
            CheckRemedy.KEEPALIVE_SETTINGS
        )
    }

    private fun batteryExemption(): Check {
        val ok = KeepAliveController.get(context).isIgnoringBatteryOptimizations()
        return Check(
            "battery",
            "Battery optimization exemption",
            ok,
            if (ok) "Exempt" else "Not exempt; clones may be killed in Doze",
            CheckSeverity.DEGRADED,
            CheckRemedy.BATTERY_EXEMPTION
        )
    }

    private fun storage(): Check {
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        val free = stat.availableBytes
        val ok = free > 200L * 1024 * 1024
        return Check(
            "storage",
            "Free storage",
            ok,
            "${free / (1024 * 1024)} MB available",
            CheckSeverity.DEGRADED,
            CheckRemedy.NONE
        )
    }

    private fun identityFactory(): Check {
        // Self-test the generator: a bad Luhn implementation would ship silently.
        val imei = IdentityFactory.randomImei()
        val ok = IdentityFactory.isValidImei(imei)
        return Check(
            "identity",
            "Identity generator",
            ok,
            if (ok) "IMEI Luhn self-test passed ($imei)"
            else "IMEI Luhn self-test FAILED ($imei)",
            CheckSeverity.INFO,
            CheckRemedy.NONE
        )
    }

    private fun instanceHealth(): Check {
        val all = manager.snapshot()
        if (all.isEmpty()) {
            return Check(
                "instances",
                "Instances",
                true,
                "No clones yet",
                CheckSeverity.DEGRADED,
                CheckRemedy.NONE
            )
        }
        val overQuota = all.count { it.isOverQuota }
        val dead = all.count { it.state == com.kiyoscripts.nexusclone.core.instance.InstanceState.DEAD }
        val ok = overQuota == 0 && dead == 0
        val detail = buildString {
            append("${all.size} clone(s)")
            if (overQuota > 0) append(", $overQuota over quota")
            if (dead > 0) append(", $dead dead")
        }
        return Check(
            "instances",
            "Instances",
            ok,
            detail,
            CheckSeverity.DEGRADED,
            CheckRemedy.NONE
        )
    }

    /**
     * Whether GMS is installed in any virtual user.
     *
     * Worth surfacing because enabling GMS is the single largest change to a user's package
     * set (thirteen packages) and it is the usual explanation for "the app list got slow".
     * Informational: not having GMS is not a failure.
     */
    private fun gmsStatus(): Check {
        val users = try {
            BlackBoxCore.get().users
        } catch (e: Exception) {
            return Check(
                "gms",
                "Google services",
                false,
                "user list unreadable: ${e.message}",
                CheckSeverity.INFO,
                CheckRemedy.NONE
            )
        }
        if (users.isEmpty()) {
            return Check(
                "gms",
                "Google services",
                true,
                "No virtual users yet",
                CheckSeverity.INFO,
                CheckRemedy.NONE
            )
        }
        val withGms = users.count {
            try {
                BlackBoxCore.get().isInstallGms(it.id)
            } catch (e: Exception) {
                false
            }
        }
        return Check(
            "gms",
            "Google services",
            true,
            if (withGms == 0) "Not installed in any of ${users.size} user(s)"
            else "Installed in $withGms of ${users.size} user(s)",
            CheckSeverity.INFO,
            CheckRemedy.NONE
        )
    }

    /** How many clones have a proxy bound, and whether the selector is installed. */
    private fun proxyStatus(): Check {
        val all = manager.snapshot()
        val bound = all.count { it.proxyId != null }
        val selector = try {
            java.net.ProxySelector.getDefault()
        } catch (e: Exception) {
            null
        }
        val installed = selector is com.kiyoscripts.nexusclone.core.net.FixedProxySelector
        // A binding with no selector in this process is inert: the clone will go out direct
        // until a guest starts and installs the selector. That is worth a degraded row, not
        // a green one.
        val ok = bound == 0 || installed
        return Check(
            "proxy",
            "Proxy routing",
            ok,
            when {
                bound == 0 -> "No clone has a proxy bound"
                installed -> "$bound clone(s) bound, selector active in this process"
                else -> "$bound clone(s) bound; selector installs when a guest starts"
            },
            if (ok) CheckSeverity.INFO else CheckSeverity.DEGRADED,
            CheckRemedy.NONE
        )
    }

    /**
     * Guest processes currently alive.
     *
     * Reads the host's own process list rather than the engine's, because the engine does not
     * expose one. A guest runs in a process named after the host package with a ":pN" suffix,
     * so counting those is an accurate view of what is actually running.
     */
    private fun processList(): Check {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mine = context.packageName
        val guests = try {
            am.runningAppProcesses
                ?.filter { it.processName.startsWith("$mine:") }
                ?.map { it.processName.removePrefix("$mine:") }
                ?: emptyList()
        } catch (e: Exception) {
            return Check(
                "processes",
                "Guest processes",
                false,
                "unreadable: ${e.message}",
                CheckSeverity.INFO,
                CheckRemedy.NONE
            )
        }
        return Check(
            "processes",
            "Guest processes",
            true,
            if (guests.isEmpty()) "None running" else guests.joinToString(", "),
            CheckSeverity.INFO,
            CheckRemedy.NONE
        )
    }

    /**
     * Whether the Java hooking stack is usable on this device.
     *
     * Epic's Offset table only accepts SDK 29 and 30 and throws otherwise, so on any other
     * Android version arbitrary Java hooking is unavailable. Reporting that plainly is more
     * useful than letting a user wonder why a module does nothing.
     */
    private fun hookStatus(): Check {
        val sdk = Build.VERSION.SDK_INT
        val supported = sdk == 29 || sdk == 30
        // ok follows the same predicate as the detail text. It used to be hardcoded true, so
        // the row showed a green check next to the word "unavailable".
        return Check(
            "hooks",
            "Java hooking",
            supported,
            if (supported) "Epic available (SDK $sdk)"
            else "Epic unavailable on SDK $sdk; only SDK 29-30 is supported",
            CheckSeverity.INFO,
            CheckRemedy.NONE
        )
    }

    /** Per-instance detail block for the diagnostics screen. */
    fun describe(inst: CloneInstance): String = buildString {
        appendLine("key: ${inst.key}")
        appendLine("state: ${inst.state}")
        appendLine("label: ${inst.label}")
        appendLine("data: ${inst.dataSizeBytes / 1024} KB")
        if (inst.quotaBytes > 0) {
            appendLine("quota: ${inst.quotaBytes / 1024} KB")
        }
        appendLine("keep-alive: ${inst.keepAliveLevel}")
        appendLine("proxy: ${inst.proxyId ?: "direct"}")
        appendLine("identity: ${inst.identityId ?: "container default"}")
        appendLine("data dir: ${manager.instanceDataDir(inst).absolutePath}")
        appendLine("exists: ${manager.instanceDataDir(inst).exists()}")
    }

    /** Memory pressure snapshot, for the resource monitor. */
    fun memory(): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return "avail ${mi.availMem / (1024 * 1024)} MB / total ${mi.totalMem / (1024 * 1024)} MB, low=${mi.lowMemory}"
    }
}
