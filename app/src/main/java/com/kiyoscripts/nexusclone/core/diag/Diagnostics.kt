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
 * One diagnostic check with a pass/fail and a human-readable detail line.
 */
data class Check(
    val id: String,
    val title: String,
    val ok: Boolean,
    val detail: String
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
     * Run every check.
     *
     * Not cheap: the engine probe is a binder round-trip and the keep-alive check reaches
     * the provider client retry loop, which can sleep for up to 2 s. Call this from a
     * background dispatcher, never from the main thread.
     */
    fun runAll(): List<Check> = buildList {
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

    private fun engineServices(): Check {
        // Probe a real binder rather than trusting areServicesAvailable(). That method used
        // to answer true unconditionally — every branch ended in `return true`, including the
        // fallback loop whose only producer always returns null — so this row reported PASS
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
            else "Probed ${ServiceManager.ACTIVITY_MANAGER}: no binder; clones cannot launch"
        )
    }

    private fun virtualRoot(): Check {
        val root = try {
            BEnvironment.getVirtualRoot()
        } catch (e: Exception) {
            return Check("virtual_root", "Virtual root writable", false, "unreadable: ${e.message}")
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
            if (ok) root.absolutePath else "cannot write to ${root.absolutePath}"
        )
    }

    private fun abiSupport(): Check {
        val abis = Build.SUPPORTED_ABIS.toList()
        val ok = abis.any { it == "arm64-v8a" || it == "armeabi-v7a" }
        return Check(
            "abi",
            "Supported ABI",
            ok,
            abis.joinToString(", ").ifEmpty { "unknown" }
        )
    }

    private fun keepAlive(): Check {
        val controller = KeepAliveController.get(context)
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
            }
        )
    }

    private fun batteryExemption(): Check {
        val ok = KeepAliveController.get(context).isIgnoringBatteryOptimizations()
        return Check(
            "battery",
            "Battery optimization exemption",
            ok,
            if (ok) "Exempt" else "Not exempt; clones may be killed in Doze"
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
            "${free / (1024 * 1024)} MB available"
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
            else "IMEI Luhn self-test FAILED ($imei)"
        )
    }

    private fun instanceHealth(): Check {
        val all = manager.snapshot()
        if (all.isEmpty()) {
            return Check("instances", "Instances", true, "No clones yet")
        }
        val overQuota = all.count { it.isOverQuota }
        val dead = all.count { it.state == com.kiyoscripts.nexusclone.core.instance.InstanceState.DEAD }
        val ok = overQuota == 0 && dead == 0
        val detail = buildString {
            append("${all.size} clone(s)")
            if (overQuota > 0) append(", $overQuota over quota")
            if (dead > 0) append(", $dead dead")
        }
        return Check("instances", "Instances", ok, detail)
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
            return Check("gms", "Google services", false, "user list unreadable: ${e.message}")
        }
        if (users.isEmpty()) {
            return Check("gms", "Google services", true, "No virtual users yet")
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
            else "Installed in $withGms of ${users.size} user(s)"
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
        return Check(
            "proxy",
            "Proxy routing",
            true,
            when {
                bound == 0 -> "No clone has a proxy bound"
                installed -> "$bound clone(s) bound, selector active in this process"
                else -> "$bound clone(s) bound; selector installs when a guest starts"
            }
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
            return Check("processes", "Guest processes", false, "unreadable: ${e.message}")
        }
        return Check(
            "processes",
            "Guest processes",
            true,
            if (guests.isEmpty()) "None running" else guests.joinToString(", ")
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
        return Check(
            "hooks",
            "Java hooking",
            true,
            if (supported) "Epic available (SDK $sdk)"
            else "Epic unavailable on SDK $sdk; only SDK 29-30 is supported"
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
