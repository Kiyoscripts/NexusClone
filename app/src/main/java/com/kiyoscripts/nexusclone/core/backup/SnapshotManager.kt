package com.kiyoscripts.nexusclone.core.backup

import android.content.Context
import android.util.Log
import com.kiyoscripts.nexusclone.core.instance.CloneInstance
import com.kiyoscripts.nexusclone.core.instance.InstanceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Snapshot and restore of a clone's data directory.
 *
 * Format: a zip (not tar, because java.util.zip is in the platform and tar is not) with
 *   manifest.json   - packageName, userId, label, createdAt, entry list with sha256
 *   data/...        - the instance data dir, paths relative to it
 *   external/...    - the instance external data dir, when present
 *
 * The manifest carries a sha256 per entry so a restore can detect a truncated or tampered
 * archive before it overwrites live data.
 */
class SnapshotManager(private val context: Context) {

    companion object {
        private const val TAG = "SnapshotManager"
        const val EXTENSION = ".vinstance"
        private const val MANIFEST = "manifest.json"
        private const val FORMAT_VERSION = 1

        /** Suffix of the per-entry staging file used while a restore is being verified. */
        private const val STAGING_SUFFIX = ".restore-tmp"

        /** Suffix for the pre-restore copy of a file the commit pass is about to replace. */
        private const val BACKUP_SUFFIX = ".restore-bak"
    }

    private val manager = InstanceManager.get(context)

    /** Default output dir: app-private, so no storage permission is needed. */
    fun defaultDir(): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "snapshots")
        .apply { mkdirs() }

    /**
     * Write a snapshot of [inst] to [outFile].
     *
     * Returns the file on success. The archive is written to a temp file, fsynced and then
     * renamed over the previous archive, so a crash mid-write never leaves a half archive
     * under the real name and never leaves the real name absent either.
     */
    suspend fun snapshot(
        inst: CloneInstance,
        outFile: File = File(defaultDir(), "${inst.userId}_${inst.packageName}$EXTENSION")
    ): Result<File> = withContext(Dispatchers.IO) {
        val tmp = File(outFile.parentFile, outFile.name + ".tmp")
        try {
            outFile.parentFile?.mkdirs()
            val entries = ArrayList<EntryRecord>()

            val dataDir = manager.instanceDataDir(inst)
            val extDir = manager.instanceExternalDir(inst)

            FileOutputStream(tmp).use { fos ->
                ZipOutputStream(BufferedOutputStream(fos)).use { zos ->
                    if (dataDir.exists()) {
                        addTree(zos, dataDir, "data", entries)
                    }
                    if (extDir.exists()) {
                        addTree(zos, extDir, "external", entries)
                    }

                    val manifest = JSONObject().apply {
                        put("formatVersion", FORMAT_VERSION)
                        put("packageName", inst.packageName)
                        put("userId", inst.userId)
                        put("label", inst.label)
                        put("createdAt", System.currentTimeMillis())
                        put("sourceCreatedAt", inst.createdAt)
                        put("entries", JSONArray().apply {
                            entries.forEach { e ->
                                put(JSONObject().apply {
                                    put("path", e.path)
                                    put("size", e.size)
                                    put("sha256", e.sha256)
                                })
                            }
                        })
                    }
                    zos.putNextEntry(ZipEntry(MANIFEST))
                    zos.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                    zos.finish()
                    zos.flush()
                    // The zip stream is finished but the bytes may still only be in the page
                    // cache; fsync while the descriptor is still open so a power loss cannot
                    // publish an empty archive under the real name.
                    fos.fd.sync()
                }
            }

            // rename(2) over an existing file is atomic on the same filesystem, so the
            // previous good archive is never absent and never truncated. Never delete
            // outFile first: a failure between the delete and the rename would leave the
            // real name missing or half written.
            if (!tmp.renameTo(outFile)) {
                tmp.delete()
                return@withContext Result.failure(
                    IOException("could not replace ${outFile.name}")
                )
            }
            Result.success(outFile)
        } catch (e: Exception) {
            Log.e(TAG, "snapshot failed: ${e.message}", e)
            tmp.delete()
            Result.failure(e)
        }
    }

    /**
     * Restore [archive] into [inst].
     *
     * The archive is opened exactly once. Each entry is hashed as it is read and the same
     * bytes are staged next to their destination; only once every entry has matched the
     * manifest sha256 are the staged files renamed into place. A mismatch aborts the whole
     * restore and deletes everything staged so far, so a corrupt or swapped archive cannot
     * half-overwrite live data.
     */
    suspend fun restore(inst: CloneInstance, archive: File): Result<Unit> =
        withContext(Dispatchers.IO) {
            val staged = ArrayList<File>()
            val committed = ArrayList<File>()
            // Original file -> its backup, for every target the commit pass overwrote. A
            // commit that fails part way must put these back: the staged files are gone by
            // then, so deleting the committed targets would destroy live data with nothing
            // to restore it from.
            val backups = LinkedHashMap<File, File>()
            try {
                val manifest = readManifest(archive)
                    ?: return@withContext Result.failure(
                        IllegalStateException("manifest.json missing or unreadable")
                    )

                val expected = HashMap<String, String>()
                val arr = manifest.optJSONArray("entries") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    expected[o.getString("path")] = o.getString("sha256")
                }

                val dataRoot = manager.instanceDataDir(inst)
                val extRoot = manager.instanceExternalDir(inst)

                // Single pass: verify and stage. The digest is computed over the exact bytes
                // that are written to the staging file, so there is no window between a
                // verification pass and an extraction pass for the archive to be swapped.
                ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zis ->
                    while (true) {
                        val entry: ZipEntry = zis.nextEntry ?: break
                        val name = entry.name
                        if (name != MANIFEST && !entry.isDirectory) {
                            val want = expected[name]
                            if (want == null) {
                                return@withContext abortRestore(
                                    staged,
                                    committed,
                                    backups,
                                    IllegalStateException("unexpected entry $name")
                                )
                            }
                            val target = resolveTarget(name, dataRoot, extRoot)
                            if (target == null) {
                                Log.w(TAG, "skipping unsafe path $name")
                            } else {
                                val tmp = File(target.parentFile, target.name + STAGING_SUFFIX)
                                val got = stageEntry(zis, tmp)
                                if (got != want) {
                                    tmp.delete()
                                    return@withContext abortRestore(
                                        staged,
                                        committed,
                                        backups,
                                        IllegalStateException("checksum mismatch on $name")
                                    )
                                }
                                staged.add(tmp)
                            }
                        }
                        zis.closeEntry()
                    }
                }

                // Commit: every entry matched, so move the staged files into place. An
                // existing target is moved aside first, so a failure part way through can be
                // rolled back to the pre-restore state instead of leaving the data dir with
                // some files replaced and the rest deleted.
                for (tmp in staged) {
                    val target = File(tmp.parentFile, tmp.name.removeSuffix(STAGING_SUFFIX))
                    if (target.exists()) {
                        val backup = File(target.parentFile, target.name + BACKUP_SUFFIX)
                        backup.delete()
                        if (!target.renameTo(backup)) {
                            return@withContext abortRestore(
                                staged,
                                committed,
                                backups,
                                IOException("could not back up ${target.name}")
                            )
                        }
                        backups[target] = backup
                    }
                    if (!tmp.renameTo(target)) {
                        return@withContext abortRestore(
                            staged,
                            committed,
                            backups,
                            IOException("could not commit ${target.name}")
                        )
                    }
                    committed.add(target)
                }
                // Success: the backups are no longer needed.
                for (backup in backups.values) {
                    if (!backup.delete() && backup.exists()) {
                        Log.w(TAG, "could not remove backup ${backup.name}")
                    }
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "restore failed: ${e.message}", e)
                abortRestore(staged, committed, backups, e)
            }
        }

    /** Read the manifest without extracting anything. */
    fun readManifest(archive: File): JSONObject? {
        if (!archive.exists()) return null
        return try {
            ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zis ->
                while (true) {
                    val entry: ZipEntry = zis.nextEntry ?: break
                    if (entry.name == MANIFEST) {
                        val text = zis.readBytes().toString(Charsets.UTF_8)
                        return JSONObject(text)
                    }
                    zis.closeEntry()
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "readManifest failed: ${e.message}")
            null
        }
    }

    /** List snapshots in the default dir. */
    fun list(): List<File> =
        defaultDir().listFiles { f -> f.name.endsWith(EXTENSION) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    private data class EntryRecord(val path: String, val size: Long, val sha256: String)

    private fun addTree(
        zos: ZipOutputStream,
        root: File,
        prefix: String,
        out: MutableList<EntryRecord>
    ) {
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (f in children) {
                if (f.isDirectory) {
                    stack.addLast(f)
                    continue
                }
                val rel = f.relativeTo(root).path.replace(File.separatorChar, '/')
                val entryName = "$prefix/$rel"
                val digest = sha256(f)
                zos.putNextEntry(ZipEntry(entryName))
                FileInputStream(f).use { it.copyTo(zos) }
                zos.closeEntry()
                out.add(EntryRecord(entryName, f.length(), digest))
            }
        }
    }

    private fun resolveTarget(name: String, dataRoot: File, extRoot: File): File? {
        // Reject traversal outright rather than normalising it away.
        if (name.contains("..")) return null
        return when {
            name.startsWith("data/") ->
                File(dataRoot, name.removePrefix("data/"))
            name.startsWith("external/") ->
                File(extRoot, name.removePrefix("external/"))
            else -> null
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { fis ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = fis.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Copy the current zip entry into [tmp] and return the sha256 of the bytes written.
     *
     * The digest is taken from the same buffer that is written to disk, so the file that is
     * later renamed into place is provably the file that was verified.
     */
    private fun stageEntry(input: java.io.InputStream, tmp: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        tmp.parentFile?.mkdirs()
        FileOutputStream(tmp).use { fos ->
            val bos = BufferedOutputStream(fos)
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
                bos.write(buf, 0, n)
            }
            bos.flush()
            fos.fd.sync()
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Undo a failed restore.
     *
     * Staged files are simply deleted. Committed targets are removed and then replaced by
     * their backup, so the data dir returns to its pre-restore contents rather than losing
     * the files the commit pass had already overwritten.
     */
    private fun abortRestore(
        staged: List<File>,
        committed: List<File>,
        backups: Map<File, File>,
        cause: Exception
    ): Result<Unit> {
        for (f in staged) {
            if (!f.delete() && f.exists()) Log.w(TAG, "could not remove staged ${f.name}")
        }
        for (f in committed) {
            if (!f.delete() && f.exists()) Log.w(TAG, "could not roll back ${f.name}")
        }
        for ((target, backup) in backups) {
            if (backup.exists() && !backup.renameTo(target)) {
                Log.w(TAG, "could not restore backup ${backup.name}")
            }
        }
        Log.e(TAG, "restore aborted: ${cause.message}", cause)
        return Result.failure(cause)
    }
}
