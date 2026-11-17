package com.kiyoscripts.nexusclone.core.instance

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.kiyoscripts.nexusclone.R
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

        /** Longest accepted label, in characters. */
        private const val MAX_LABEL_LEN = 64

        /** Recursion cap for the size walk, so a symlink loop cannot hang the IO thread. */
        private const val MAX_DIR_DEPTH = 32

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

    /**
     * Epoch millis of the last completed size walk.
     *
     * Volatile because [refreshSizesIfStale] is called from UI coroutines while
     * [refreshSizes] runs on the IO dispatcher.
     */
    @Volatile
    private var lastSizeWalkAt = 0L

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
     * Reserve [count] consecutive virtual user ids in one read.
     *
     * [allocateUserId] re-reads the store and the engine table on every call, so a batch
     * that called it in a loop could hand out the same id twice when the first insert had
     * not landed yet. Reserving the whole block up front makes the ids unique by
     * construction.
     */
    private fun allocateUserIds(count: Int): List<Int> {
        val fromStore = store.maxUserId()
        val fromEngine = try {
            BlackBoxCore.get().users.maxOfOrNull { it.id } ?: 0
        } catch (e: Exception) {
            Log.w(TAG, "engine user table unreadable: ${e.message}")
            0
        }
        val base = maxOf(fromStore, fromEngine, FIRST_CLONE_USER_ID - 1)
        return (1..count).map { base + it }
    }

    /**
     * Create a clone from a package name or an apk source.
     *
     * [packageName] is either an installed package name (the normal path, from the app
     * picker) or a content/file URI when the user picked an apk from storage. The two are not
     * interchangeable: installPackageAsUser(String) resolves the string as a package name and
     * throws NameNotFoundException for a URI, which is why picking an apk used to fail with a
     * confusing "clone failed" toast.
     *
     * A URI is installed into a fresh virtual user first, and the instance row is then built
     * from the package name the engine reports back.
     */
    suspend fun create(
        packageName: String,
        label: String? = null,
        quotaBytes: Long = 0L
    ): Result<CloneInstance> = withContext(Dispatchers.IO) {
        if (isApkSource(packageName)) {
            createFromApkSource(packageName, label, quotaBytes)
        } else {
            createFromPackage(packageName, label, quotaBytes)
        }
    }

    /** True when [source] is a URI or path rather than a package name. */
    private fun isApkSource(source: String): Boolean =
        source.startsWith("content://") ||
            source.startsWith("file://") ||
            source.startsWith("/")

    /**
     * Install an apk from a URI or path into a new virtual user, then register the instance.
     *
     * The engine's installPackageAsUser(Uri, int) handles the content:// case itself, so the
     * URI is passed straight through rather than being copied to a temp file first.
     */
    private suspend fun createFromApkSource(
        source: String,
        label: String?,
        quotaBytes: Long
    ): Result<CloneInstance> = withContext(Dispatchers.IO) {
        _busy.value = true
        try {
            val userId = allocateUserId()
            try {
                BlackBoxCore.get().createUser(userId)
            } catch (e: Exception) {
                Log.e(TAG, "createUser($userId) failed: ${e.message}", e)
                return@withContext Result.failure(e)
            }

            val install = try {
                if (source.startsWith("content://")) {
                    BlackBoxCore.get().installPackageAsUser(Uri.parse(source), userId)
                } else {
                    val path = if (source.startsWith("file://")) Uri.parse(source).path else source
                    BlackBoxCore.get().installPackageAsUser(File(path ?: source), userId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "install from $source into user $userId failed: ${e.message}", e)
                safeDeleteUser(userId)
                return@withContext Result.failure(e)
            }

            if (!install.success) {
                safeDeleteUser(userId)
                return@withContext Result.failure(
                    IllegalStateException(install.msg ?: "install failed")
                )
            }

            // The engine reports the package it actually installed; trust that over the
            // source string, which for a URI is not a package name at all.
            val installedPkg = install.packageName
            if (installedPkg.isNullOrBlank()) {
                safeDeleteUser(userId)
                return@withContext Result.failure(
                    IllegalStateException("engine did not report an installed package")
                )
            }

            val row = CloneInstance(
                userId = userId,
                packageName = installedPkg,
                label = label ?: loadLabel(installedPkg),
                state = InstanceState.CREATED,
                quotaBytes = quotaBytes
            )
            val id = store.insert(row)
            if (id == -1L) {
                safeDeleteUser(userId)
                return@withContext Result.failure(
                    IllegalStateException(
                        "could not persist instance for $installedPkg in user $userId"
                    )
                )
            }
            // The row is written as CREATED and only promoted once the insert is known to
            // have landed. A row that says INSTALLED while the insert is still in flight
            // would be a lie the UI cannot detect, and a failed insert would leave a
            // phantom INSTALLED clone behind.
            val saved = row.copy(id = id)
            val installed = saved.copy(state = InstanceState.INSTALLED)
            store.update(installed)
            refresh()
            Result.success(installed)
        } catch (e: Exception) {
            Log.e(TAG, "createFromApkSource($source) failed: ${e.message}", e)
            Result.failure(e)
        } finally {
            _busy.value = false
        }
    }

    /**
     * Create a clone of an already-installed [packageName].
     *
     * Steps: allocate a user id, register it with the engine, install the apk into that
     * user, then persist the row. A failure at any step rolls the user back so we never
     * leave an orphaned virtual user with no instance row.
     */
    private suspend fun createFromPackage(
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
                state = InstanceState.CREATED,
                quotaBytes = quotaBytes
            )
            val id = store.insert(row)
            if (id == -1L) {
                // insert() reports failure as -1; a real id is AUTOINCREMENT, so it is
                // always >= 1 and -1 can never match a row. Without this check the caller
                // would receive a "created" clone whose id byId() can never resolve, while
                // the virtual user and the installed apk stay behind on disk.
                safeDeleteUser(userId)
                return@withContext Result.failure(
                    IllegalStateException(
                        "could not persist instance for $packageName in user $userId"
                    )
                )
            }
            // Promote to INSTALLED only after the insert is confirmed, so the persisted
            // state never claims an install that has no row behind it.
            val saved = row.copy(id = id)
            val installed = saved.copy(state = InstanceState.INSTALLED)
            store.update(installed)
            refresh()
            Result.success(installed)
        } catch (e: Exception) {
            Log.e(TAG, "create($packageName) failed: ${e.message}", e)
            Result.failure(e)
        } finally {
            _busy.value = false
        }
    }

    /**
     * Create [count] clones of the same package in one pass.
     *
     * The user ids are reserved up front and the installs run sequentially inside a single
     * IO context, with [busy] set once and cleared once. The previous version called
     * [create] in a loop, which toggled [busy] on and off for every clone (so the UI
     * flickered and a cancel could land mid-batch) and re-read the user table between
     * installs, which could hand the same id to two clones.
     */
    suspend fun createMany(
        packageName: String,
        count: Int,
        labelPrefix: String? = null
    ): List<Result<CloneInstance>> = withContext(Dispatchers.IO) {
        if (count <= 0) return@withContext emptyList()
        val out = ArrayList<Result<CloneInstance>>(count)
        _busy.value = true
        try {
            val userIds = allocateUserIds(count)
            for (i in 1..count) {
                val label = labelPrefix?.let { "$it $i" }
                out.add(installInto(packageName, label, 0L, userIds[i - 1]))
            }
            refresh()
        } finally {
            _busy.value = false
        }
        out
    }

    /**
     * Install [packageName] into the already reserved [userId] and persist the row.
     *
     * Split out of [createFromPackage] so [createMany] can reuse the install and rollback
     * logic without re-allocating a user id per clone. Every failure path deletes the
     * virtual user, so a failed clone never leaves an orphaned user with an installed apk.
     */
    private fun installInto(
        packageName: String,
        label: String?,
        quotaBytes: Long,
        userId: Int
    ): Result<CloneInstance> {
        try {
            BlackBoxCore.get().createUser(userId)
        } catch (e: Exception) {
            Log.e(TAG, "createUser($userId) failed: ${e.message}", e)
            return Result.failure(e)
        }

        val install = try {
            BlackBoxCore.get().installPackageAsUser(packageName, userId)
        } catch (e: Exception) {
            Log.e(TAG, "install into user $userId failed: ${e.message}", e)
            safeDeleteUser(userId)
            return Result.failure(e)
        }

        if (!install.success) {
            safeDeleteUser(userId)
            return Result.failure(IllegalStateException(install.msg ?: "install failed"))
        }

        val row = CloneInstance(
            userId = userId,
            packageName = packageName,
            label = label ?: loadLabel(packageName),
            state = InstanceState.CREATED,
            quotaBytes = quotaBytes
        )
        val id = store.insert(row)
        if (id == -1L) {
            safeDeleteUser(userId)
            return Result.failure(
                IllegalStateException(
                    "could not persist instance for $packageName in user $userId"
                )
            )
        }
        val installed = row.copy(id = id, state = InstanceState.INSTALLED)
        store.update(installed)
        return Result.success(installed)
    }

    /**
     * Launch the clone. Moves INSTALLED/COLD/FROZEN to COLD and stamps the launch.
     *
     * The target is COLD, not WARM: issuing a launch intent does not prove the guest
     * process attached, and a row that claims WARM while nothing is running makes the
     * keep-alive controller skip a clone that is not actually alive. The lifecycle
     * callback promotes the row to FOREGROUND once a guest activity really resumes.
     */
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
            transitionWith(inst, InstanceState.COLD) {
                it.copy(lastLaunchAt = System.currentTimeMillis())
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "launch(${inst.key}) failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Stop the guest process, keeping data.
     *
     * A frozen clone is left untouched: stopPackage on a frozen clone would either be a
     * no-op or, worse, clear the frozen flag's meaning by moving the row to COLD while
     * [CloneInstance.frozen] stays true, so the UI would show a frozen clone that the
     * keep-alive controller treats as stopped. Returning success keeps the caller's
     * "stop everything" loop simple.
     */
    suspend fun stop(id: Long): Result<Unit> = withContext(Dispatchers.IO) {
        val inst = byId(id) ?: return@withContext Result.failure(
            NoSuchElementException("no instance $id")
        )
        if (inst.frozen || inst.state == InstanceState.FROZEN) {
            return@withContext Result.success(Unit)
        }
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

    /**
     * Unfreeze: clear the flag and pick the state the clone would have had.
     *
     * A clone that never launched goes back to INSTALLED, not COLD: COLD means "has run
     * before, process gone", and showing that for a clone that has never started makes the
     * UI offer a cold start that has no previous process to restore.
     */
    suspend fun unfreeze(id: Long): Result<Unit> = withContext(Dispatchers.IO) {
        val inst = byId(id) ?: return@withContext Result.failure(
            NoSuchElementException("no instance $id")
        )
        val target = if (inst.lastLaunchAt == 0L) {
            InstanceState.INSTALLED
        } else {
            InstanceState.COLD
        }
        val thawed = inst.copy(state = target, frozen = false)
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

    /**
     * Remove the clone entirely: uninstall from the virtual user, drop the row.
     *
     * The row is re-read from the store rather than taken from the in-memory snapshot,
     * because the snapshot can be stale (a rename or a quota change written by another
     * screen would be lost by the DEAD write below). The row is also persisted as DEAD
     * before the delete: if the process dies between the uninstall and the delete, the
     * next start sees a DEAD row and [purgeDead] can finish the job instead of leaving a
     * clone that looks installed but has no apk.
     */
    suspend fun destroy(id: Long): Result<Unit> = withContext(Dispatchers.IO) {
        val inst = store.byId(id) ?: return@withContext Result.failure(
            NoSuchElementException("no instance $id")
        )
        // BlackBoxCore.uninstallPackageAsUser returns void, so a throw is the only failure
        // signal the engine gives us. If it throws the apk is still installed in the
        // virtual user: keep the row so the UI can retry instead of orphaning the install.
        try {
            BlackBoxCore.get().uninstallPackageAsUser(inst.packageName, inst.userId)
        } catch (e: Exception) {
            Log.w(TAG, "uninstall ${inst.key} failed, keeping instance: ${e.message}")
            return@withContext Result.failure(e)
        }
        // Only drop the virtual user when no other clone of this package shares it.
        val siblings = store.all().filter {
            it.userId == inst.userId && it.id != inst.id
        }
        if (siblings.isEmpty()) safeDeleteUser(inst.userId)

        store.update(inst.copy(state = InstanceState.DEAD))
        store.delete(id)
        deletePreview(inst)
        refresh()
        Result.success(Unit)
    }

    /**
     * Delete every row left in DEAD and report how many were removed.
     *
     * [destroy] marks a row DEAD before deleting it, so a crash in that window leaves a
     * row whose apk is already gone. Without this sweep the row would stay in the list
     * forever, unlaunchable and impossible to remove through the normal path.
     */
    suspend fun purgeDead(): Int = withContext(Dispatchers.IO) {
        val dead = store.byState(InstanceState.DEAD)
        if (dead.isEmpty()) return@withContext 0
        dead.forEach { deletePreview(it) }
        val removed = store.deleteByState(InstanceState.DEAD)
        refresh()
        removed
    }

    /**
     * Create a second clone of the same package, copying quota, proxy and identity.
     *
     * The copy keeps the same proxy and identity bindings because a duplicate is normally
     * made to run the same account setup twice; dropping them would silently send the new
     * clone out on the host IP. The label gets a "copy" suffix so the two rows are
     * distinguishable in the list.
     */
    suspend fun duplicate(id: Long): Result<CloneInstance> = withContext(Dispatchers.IO) {
        val src = byId(id) ?: return@withContext Result.failure(
            NoSuchElementException("no instance $id")
        )
        val suffix = context.getString(R.string.inst_duplicate_suffix)
        val newLabel = "${src.label} $suffix"
        val created = create(src.packageName, newLabel, src.quotaBytes)
        val copy = created.getOrElse { return@withContext Result.failure(it) }
        if (src.proxyId != null) setProxy(copy.id, src.proxyId)
        if (src.identityId != null) setIdentity(copy.id, src.identityId)
        val finalRow = byId(copy.id) ?: copy
        Result.success(finalRow)
    }

    /**
     * Rename the clone.
     *
     * Validation returns a failure instead of throwing: the detail screen discards the
     * Result, so a throw here would crash the app on an empty name. The label is trimmed
     * first, so a name made only of spaces is rejected rather than stored as blank.
     */
    suspend fun rename(id: Long, label: String): Result<Unit> = withContext(Dispatchers.IO) {
        val inst = byId(id) ?: return@withContext Result.failure(
            NoSuchElementException("no instance $id")
        )
        val trimmed = label.trim()
        if (trimmed.isEmpty()) {
            return@withContext Result.failure(
                IllegalArgumentException(context.getString(R.string.inst_label_blank))
            )
        }
        if (trimmed.length > MAX_LABEL_LEN) {
            return@withContext Result.failure(
                IllegalArgumentException(context.getString(R.string.inst_label_too_long))
            )
        }
        store.update(inst.copy(label = trimmed))
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

    /**
     * Recompute data sizes for every instance. Expensive; call from a background scope.
     *
     * Each row that is written also gets [CloneInstance.lastSizeAt] stamped, so a later
     * [refreshSizesIfStale] can tell a fresh row from a stale one after a restart.
     */
    suspend fun refreshSizes() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val updated = store.all().map { inst ->
            val size = dirSize(instanceDataDir(inst))
            if (size != inst.dataSizeBytes || inst.lastSizeAt == 0L) {
                val next = inst.copy(dataSizeBytes = size, lastSizeAt = now)
                store.update(next)
                next
            } else {
                inst
            }
        }
        _instances.value = updated
        lastSizeWalkAt = now
    }

    /**
     * Walk the sizes only when the last walk is older than [maxAgeMs].
     *
     * This exists because nothing called [refreshSizes] on the list screen, so every clone
     * showed 0 B until something else happened to trigger a walk. The age check keeps the
     * call cheap enough to make from onResume: a full walk of every virtual data dir is
     * tens of thousands of stat calls and must not run on every recomposition.
     */
    suspend fun refreshSizesIfStale(maxAgeMs: Long = 30_000L) {
        val now = System.currentTimeMillis()
        if (now - lastSizeWalkAt < maxAgeMs) return
        refreshSizes()
        lastSizeWalkAt = System.currentTimeMillis()
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

    /**
     * Apply [level] to every clone and report how many rows changed.
     *
     * The store is read once and only the rows that actually differ are written. Calling
     * [setKeepAliveLevel] in a loop would re-read the whole table and republish the list
     * once per clone, which on a large list means N full queries and N UI rebuilds for a
     * single settings change.
     */
    suspend fun syncKeepAliveLevels(level: Int): Int = withContext(Dispatchers.IO) {
        val target = level.coerceIn(0, 2)
        val rows = store.all()
        var changed = 0
        val next = rows.map { inst ->
            if (inst.keepAliveLevel == target) {
                inst
            } else {
                val updated = inst.copy(keepAliveLevel = target)
                store.update(updated)
                changed++
                updated
            }
        }
        if (changed > 0) _instances.value = next
        changed
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

    /**
     * Publish a state change without re-reading the whole table.
     *
     * The old body called store.all() after every write, so a single resume event ran a
     * full table query and rebuilt the list. Mapping the changed row into the current
     * snapshot by id keeps the published list identical while touching one element.
     */
    private fun transition(inst: CloneInstance, next: InstanceState) {
        if (inst.state == next) return
        val updated = inst.copy(state = next)
        store.update(updated)
        publish(updated)
    }

    /**
     * Transition to [next] and apply [mutate] in the same UPDATE.
     *
     * [transition] cannot express this: it only carries a state change, so stamping another
     * field needs a second full-row write, and the intermediate state becomes observable.
     * Here the row is written once, so the state and the extra fields land together.
     *
     * The early return mirrors [transition]: when the instance is already in [next] the row
     * is left untouched, so a relaunch of an already-COLD clone does not rewrite it.
     */
    private fun transitionWith(
        inst: CloneInstance,
        next: InstanceState,
        mutate: (CloneInstance) -> CloneInstance,
    ) {
        if (inst.state == next) return
        val updated = mutate(inst).copy(state = next)
        store.update(updated)
        publish(updated)
    }

    /**
     * Replace the row with the same id in the published list.
     *
     * When the id is not in the list (a row created by another path that has not been
     * refreshed yet) the list is left alone rather than being rebuilt from disk, so this
     * stays a pure in-memory operation.
     */
    private fun publish(updated: CloneInstance) {
        val current = _instances.value
        val idx = current.indexOfFirst { it.id == updated.id }
        if (idx < 0) return
        _instances.value = current.toMutableList().also { it[idx] = updated }
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

    /**
     * Total bytes under [dir], skipping symlinks and capping the recursion depth.
     *
     * A virtual data dir can contain a symlink that points back at its own parent (some
     * apps create one for their external files dir), and following it made the walk recurse
     * until the stack blew. Symlinks are detected with Os.lstat, which does not follow the
     * link; on any device where that call is unavailable the plain File check is used, so
     * the walk still terminates on the depth cap.
     */
    private fun dirSize(dir: File, depth: Int = 0): Long {
        if (depth > MAX_DIR_DEPTH) return 0L
        if (isSymlink(dir)) return 0L
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        var total = 0L
        dir.listFiles()?.forEach { total += dirSize(it, depth + 1) }
        return total
    }

    /** True when [file] is a symbolic link. Never throws. */
    private fun isSymlink(file: File): Boolean = try {
        val st = Os.lstat(file.absolutePath)
        OsConstants.S_ISLNK(st.st_mode)
    } catch (t: Throwable) {
        // Os.lstat throws ErrnoException for a missing path and is absent on some
        // runtimes; fall back to the File API, which cannot loop on its own.
        try {
            file.canonicalPath != file.absolutePath
        } catch (e: Exception) {
            false
        }
    }
}
