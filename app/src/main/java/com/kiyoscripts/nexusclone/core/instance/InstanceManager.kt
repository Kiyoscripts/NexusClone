package com.kiyoscripts.nexusclone.core.instance

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.core.env.BEnvironment
import java.io.File

/**
 * Owns every clone instance and every state transition.
 *
 * This is the only class allowed to write [InstanceState]. Everything else observes
 * [instances] and calls the intent methods below.
 */
class InstanceManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "InstanceManager"

        /** Virtual user ids start here; 0 is the host user and is never a clone. */
        private const val FIRST_CLONE_USER_ID = 1

        @Volatile
        private var INSTANCE: InstanceManager? = null

        fun get(context: Context): InstanceManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: InstanceManager(context.applicationContext).also { INSTANCE = it }
            }
    }

    private val store = InstanceStore(context)

    private val _instances = MutableStateFlow<List<CloneInstance>>(emptyList())
    val instances: StateFlow<List<CloneInstance>> = _instances.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Reload from disk. Safe to call from any thread. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        _instances.value = store.all()
    }

    fun snapshot(): List<CloneInstance> = _instances.value

    fun byPackage(packageName: String): List<CloneInstance> =
        _instances.value.filter { it.packageName == packageName }

    fun byId(id: Long): CloneInstance? = _instances.value.firstOrNull { it.id == id }

    fun byKey(userId: Int, packageName: String): CloneInstance? =
        _instances.value.firstOrNull { it.userId == userId && it.packageName == packageName }

    /**
     * Allocate the next free virtual user id.
     *
     * BlackBox keeps its own user table; we take the max of both our store and BlackBox's
     * table so a reinstall of the app does not hand out an id that already has data on disk.
     */
    private fun allocateUserId(): Int {
        val fromStore = store.maxUserId()
        val fromEngine = try {
            BlackBoxCore.get().users.maxOfOrNull { it.id } ?: 0
        } catch (e: Exception) {
            Log.w(TAG, "engine user table unreadable: ${e.message}")
            0
        }
        return maxOf(fromStore, fromEngine, FIRST_CLONE_USER_ID - 1) + 1
    }

    /**
     * Create a clone of [packageName].
     *
     * Steps: allocate a user id, register it with the engine, install the apk into that
     * user, then persist the row. A failure at any step rolls the user back so we never
     * leave an orphaned virtual user with no instance row.
     */
    suspend fun create(
        packageName: String,
        label: String? = null,
        quotaBytes: Long = 0L
    ): Result<CloneInstance> = withContext(Dispatchers.IO) {
        _busy.value = true
        try {
            val resolvedLabel = label ?: loadLabel(packageName)
            val userId = allocateUserId()

            try {
                BlackBoxCore.get().createUser(userId)
            } catch (e: Exception) {
                Log.e(TAG, "createUser($userId) failed: ${e.message}", e)
                return@withContext Result.failure(e)
            }

            val install = try {
                BlackBoxCore.get().installPackageAsUser(packageName, userId)
            } catch (e: Exception) {
                Log.e(TAG, "install into user $userId failed: ${e.message}", e)
                safeDeleteUser(userId)
                return@withContext Result.failure(e)
            }

            if (!install.success) {
                safeDeleteUser(userId)
                return@withContext Result.failure(
                    IllegalStateException(install.msg ?: "install failed")
                )
            }

            val row = CloneInstance(
                userId = userId,
                packageName = packageName,
                label = resolvedLabel,
                state = InstanceState.INSTALLED,
                quotaBytes = quotaBytes
            )
            val id = store.insert(row)
            val saved = row.copy(id = id)
            refresh()
            Result.success(saved)
        } catch (e: Exception) {
            Log.e(TAG, "create($packageName) failed: ${e.message}", e)
            Result.failure(e)
        } finally {
            _busy.value = false
        }
    }

    /** Create [count] clones of the same package in one pass. */
    suspend fun createMany(
        packageName: String,
        count: Int,
        labelPrefix: String? = null
    ): List<Result<CloneInstance>> = withContext(Dispatchers.IO) {
        val out = ArrayList<Result<CloneInstance>>(count)
        for (i in 1..count) {
            val label = labelPrefix?.let { "$it $i" }
            out.add(create(packageName, label))
        }
        out
    }

    /** Launch the clone. Moves INSTALLED/COLD/FROZEN to COLD then WARM on attach. */
    suspend fun launch(id: Long): Result<Unit> = withContext(Dispatchers.IO) {
        val inst = byId(id) ?: return@withContext Result.failure(
            NoSuchElementException("no instance $id")
        )
        if (!inst.state.isLaunchable) {
            return@withContext Result.failure(
                IllegalStateException("instance ${inst.key} is ${inst.state}")
            )
        }
        try {
            val ok = BlackBoxCore.get().launchApk(inst.packageName, inst.userId)
            if (!ok) {
                return@withContext Result.failure(
                    IllegalStateException("no launch intent for ${inst.packageName}")
                )
            }
            transition(inst, InstanceState.COLD)
            transition(inst.copy(lastLaunchAt = System.currentTimeMillis()), InstanceState.WARM)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "launch(${inst.key}) failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /** Stop the guest process, keeping data. */
    suspend fun stop(id: Long): Result<Unit> = withContext(Dispatchers.IO) {
        val inst = byId(id) ?: return@withContext Result.failure(
            NoSuchElementException("no instance $id")
        )
        try {
            BlackBoxCore.get().stopPackage(inst.packageName, inst.userId)
            transition(inst, InstanceState.COLD)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Freeze: stop the process and mark the row frozen so keep-alive skips it. */
    suspend fun freeze(id: Long): Result<Unit> = withContext(Dispatchers.IO) {
        val inst = byId(id) ?: return@withContext Result.failure(
            NoSuchElementException("no instance $id")
        )
        try {
            BlackBoxCore.get().stopPackage(inst.packageName, inst.userId)
            val frozen = inst.copy(state = InstanceState.FROZEN, frozen = true)
            store.update(frozen)
            refresh()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Unfreeze: clear the flag, leave the instance COLD so the next launch is a cold start. */
    suspend fun unfreeze(id: Long): Result<Unit> = withContext(Dispatchers.IO) {
        val inst = byId(id) ?: return@withContext Result.failure(
            NoSuchElementException("no instance $id")
        )
        val thawed = inst.copy(state = InstanceState.COLD, frozen = false)
        store.update(thawed)
        refresh()
        Result.success(Unit)
    }

    /** Wipe the clone's data dir but keep the instance registered. */
    suspend fun clearData(id: Long): Result<Unit> = withContext(Dispatchers.IO) {
        val inst = byId(id) ?: return@withContext Result.failure(
            NoSuchElementException("no instance $id")
        )
        try {
            BlackBoxCore.get().clearPackage(inst.packageName, inst.userId)
            transition(inst, InstanceState.INSTALLED)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Remove the clone entirely: uninstall from the virtual user, drop the row. */
    suspend fun destroy(id: Long): Result<Unit> = withContext(Dispatchers.IO) {
        val inst = byId(id) ?: return@withContext Result.failure(
            NoSuchElementException("no instance $id")
        )
        try {
            BlackBoxCore.get().uninstallPackageAsUser(inst.packageName, inst.userId)
        } catch (e: Exception) {
            Log.w(TAG, "uninstall ${inst.key} failed, continuing: ${e.message}")
        }
        // Only drop the virtual user when no other clone of this package shares it.
        val siblings = store.all().filter {
            it.userId == inst.userId && it.id != inst.id
        }
        if (siblings.isEmpty()) safeDeleteUser(inst.userId)

        store.delete(id)
        deletePreview(inst)
        refresh()
        Result.success(Unit)
    }

    /** Rename the clone. */
    suspend fun rename(id: Long, label: String): Result<Unit> = withContext(Dispatchers.IO) {
        val inst = byId(id) ?: return@withContext Result.failure(
            NoSuchElementException("no instance $id")
        )
        store.update(inst.copy(label = label))
        refresh()
        Result.success(Unit)
    }

    /** Set a soft storage quota in bytes; 0 disables it. */
    suspend fun setQuota(id: Long, quotaBytes: Long): Result<Unit> = withContext(Dispatchers.IO) {
        val inst = byId(id) ?: return@withContext Result.failure(
            NoSuchElementException("no instance $id")
        )
        store.update(inst.copy(quotaBytes = quotaBytes))
        refresh()
        Result.success(Unit)
    }

    /** Bind a proxy profile to the clone. */
    suspend fun setProxy(id: Long, proxyId: Long?): Result<Unit> = withContext(Dispatchers.IO) {
        val inst = byId(id) ?: return@withContext Result.failure(
            NoSuchElementException("no instance $id")
        )
        store.update(inst.copy(proxyId = proxyId))
        refresh()
        Result.success(Unit)
    }

    /** Bind an identity profile to the clone. */
    suspend fun setIdentity(id: Long, identityId: Long?): Result<Unit> = withContext(Dispatchers.IO) {
        val inst = byId(id) ?: return@withContext Result.failure(
            NoSuchElementException("no instance $id")
        )
        store.update(inst.copy(identityId = identityId))
        refresh()
        Result.success(Unit)
    }

    /** Recompute data sizes for every instance. Expensive; call from a background scope. */
    suspend fun refreshSizes() = withContext(Dispatchers.IO) {
        val updated = store.all().map { inst ->
            val size = dirSize(instanceDataDir(inst))
            if (size != inst.dataSizeBytes) {
                val next = inst.copy(dataSizeBytes = size)
                store.update(next)
                next
            } else {
                inst
            }
        }
        _instances.value = updated
    }

    /** Absolute path of the clone's private data dir inside the virtual root. */
    fun instanceDataDir(inst: CloneInstance): File =
        BEnvironment.getDataDir(inst.packageName, inst.userId)

    /** Absolute path of the clone's external data dir inside the virtual root. */
    fun instanceExternalDir(inst: CloneInstance): File =
        BEnvironment.getExternalDataDir(inst.packageName, inst.userId)

    /** Record a new preview thumbnail path. */
    suspend fun setPreview(id: Long, relativePath: String?) = withContext(Dispatchers.IO) {
        val inst = byId(id) ?: return@withContext
        store.update(
            inst.copy(
                previewPath = relativePath,
                previewUpdatedAt = System.currentTimeMillis()
            )
        )
        refresh()
    }

    /** Record keep-alive strength 0..2. */
    suspend fun setKeepAliveLevel(id: Long, level: Int) = withContext(Dispatchers.IO) {
        val inst = byId(id) ?: return@withContext
        store.update(inst.copy(keepAliveLevel = level.coerceIn(0, 2)))
        refresh()
    }

    /** Called by the lifecycle callback when a guest activity resumes. */
    suspend fun onGuestResumed(packageName: String, userId: Int) = withContext(Dispatchers.IO) {
        val inst = byKey(userId, packageName) ?: return@withContext
        transition(inst, InstanceState.FOREGROUND)
    }

    /** Called by the lifecycle callback when a guest activity pauses. */
    suspend fun onGuestPaused(packageName: String, userId: Int) = withContext(Dispatchers.IO) {
        val inst = byKey(userId, packageName) ?: return@withContext
        transition(inst, InstanceState.BACKGROUND)
    }

    private fun transition(inst: CloneInstance, next: InstanceState) {
        if (inst.state == next) return
        store.update(inst.copy(state = next))
        _instances.value = store.all()
    }

    private fun safeDeleteUser(userId: Int) {
        try {
            BlackBoxCore.get().deleteUser(userId)
        } catch (e: Exception) {
            Log.w(TAG, "deleteUser($userId) failed: ${e.message}")
        }
    }

    private fun deletePreview(inst: CloneInstance) {
        val rel = inst.previewPath ?: return
        try {
            File(previewDir(), rel).delete()
        } catch (e: Exception) {
            Log.w(TAG, "preview delete failed: ${e.message}")
        }
    }

    fun previewDir(): File =
        File(context.cacheDir, "previews").apply { mkdirs() }

    private fun loadLabel(packageName: String): String = try {
        val ai = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(ai).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        packageName
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        var total = 0L
        dir.listFiles()?.forEach { total += dirSize(it) }
        return total
    }
}
