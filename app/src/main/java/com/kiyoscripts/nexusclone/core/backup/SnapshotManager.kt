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
    }

    private val manager = InstanceManager.get(context)

    /** Default output dir: app-private, so no storage permission is needed. */
    fun defaultDir(): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "snapshots")
        .apply { mkdirs() }

    /**
     * Write a snapshot of [inst] to [outFile].
     *
     * Returns the file on success. The archive is written to a temp file and renamed, so a
     * crash mid-write never leaves a half archive under the real name.
     */
    suspend fun snapshot(
        inst: CloneInstance,
        outFile: File = File(defaultDir(), "${inst.userId}_${inst.packageName}$EXTENSION")
    ): Result<File> = withContext(Dispatchers.IO) {
        val tmp = File(outFile.parentFile, outFile.name + ".tmp")
        try {
            outFile.parentFile?.mkdirs()
            val entries = ArrayList<EntryRecord>()

            ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { zos ->
                val dataDir = manager.instanceDataDir(inst)
                val extDir = manager.instanceExternalDir(inst)

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
            }

            if (outFile.exists()) outFile.delete()
            if (!tmp.renameTo(outFile)) {
                tmp.copyTo(outFile, overwrite = true)
                tmp.delete()
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
     * Verifies every entry against the manifest sha256 before writing anything. A mismatch
     * aborts the whole restore, so a corrupt archive cannot half-overwrite live data.
     */
    suspend fun restore(inst: CloneInstance, archive: File): Result<Unit> =
        withContext(Dispatchers.IO) {
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

                // Pass 1: verify.
                ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zis ->
                    var entry: ZipEntry? = zis.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (name != MANIFEST && !entry.isDirectory) {
                            val want = expected[name]
                            if (want == null) {
                                return@withContext Result.failure(
                                    IllegalStateException("unexpected entry $name")
                                )
                            }
                            val got = sha256(zis)
                            if (got != want) {
                                return@withContext Result.failure(
                                    IllegalStateException("checksum mismatch on $name")
                                )
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }

                // Pass 2: extract.
                val dataRoot = manager.instanceDataDir(inst)
                val extRoot = manager.instanceExternalDir(inst)
                ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zis ->
                    var entry: ZipEntry? = zis.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (name != MANIFEST && !entry.isDirectory) {
                            val target = resolveTarget(name, dataRoot, extRoot)
                            if (target == null) {
                                Log.w(TAG, "skipping unsafe path $name")
                            } else {
                                target.parentFile?.mkdirs()
                                BufferedOutputStream(FileOutputStream(target)).use { bos ->
                                    zis.copyTo(bos)
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "restore failed: ${e.message}", e)
                Result.failure(e)
            }
        }

    /** Read the manifest without extracting anything. */
    fun readManifest(archive: File): JSONObject? {
        if (!archive.exists()) return null
        return try {
            ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    if (entry.name == MANIFEST) {
                        val text = zis.readBytes().toString(Charsets.UTF_8)
                        return JSONObject(text)
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
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

    private fun sha256(input: java.io.InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
