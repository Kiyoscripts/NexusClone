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

    /** Run every check. Cheap enough to call on screen open. */
    fun runAll(): List<Check> = buildList {
        add(engineServices())
        add(virtualRoot())
        add(abiSupport())
        add(keepAlive())
        add(batteryExemption())
        add(storage())
        add(identityFactory())
        add(instanceHealth())
    }

    private fun engineServices(): Check {
        val ok = try {
            BlackBoxCore.get().areServicesAvailable()
        } catch (e: Exception) {
            Log.w(TAG, "areServicesAvailable threw: ${e.message}")
            false
        }
        return Check(
            "engine_services",
            "Container services bound",
            ok,
            if (ok) "BlackBox core services are reachable"
            else "Core services are not bound; clones cannot launch"
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
        val level = KeepAliveController.get(context).strength()
        return Check(
            "keepalive",
            "Keep-alive strength",
            level >= 1,
            when (level) {
                2 -> "Foreground service running, battery exemption granted"
                1 -> "Foreground service running, no battery exemption"
                else -> "Foreground service not running"
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
