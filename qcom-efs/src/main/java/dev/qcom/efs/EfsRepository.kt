package dev.qcom.efs

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EfsRepository(private val ctx: Context) {

    private val client = EfsClient()

    val connected: Boolean get() = client.isConnected

    /** Kept so a failed connect can still show what the root shell said. */
    var lastStartLog: List<String> = emptyList()
        private set
    var lastEnforce: Int? = null
        private set

    // ---- session -------------------------------------------------------

    suspend fun connect(verbose: Boolean): Pair<DaemonInfo, List<String>> {
        val report = RootDaemon.start(ctx, verbose)
        lastStartLog = report.lines
        lastEnforce = report.enforce
        if (!report.ok) throw EfsException(report.error ?: "the helper did not start")

        client.connect()
        val version = client.cmd("version").optString("version", "?")
        val open = client.cmd("open")

        val info = DaemonInfo(
            version = version,
            subsys = open.optInt("subsys"),
            transport = open.optString("transport"),
            readOnly = open.optBoolean("readonly", true),
            maxInline = open.optInt("max_inline", DEFAULT_MAX_INLINE),
        )
        return info to (report.lines + RootDaemon.readDaemonLog(ctx))
    }

    suspend fun disconnect() {
        runCatching { client.cmd("shutdown") }
        client.close()
        RootDaemon.stop()
    }

    /** The SELinux mode as root saw it at connect time; the app never changes it. */
    fun enforceState(): Int? = lastEnforce

    suspend fun setReadOnly(on: Boolean) {
        client.cmd("readonly") { put("on", on) }
    }

    suspend fun stats(): JSONObject = client.cmd("stats")

    fun daemonLog(): List<String> = RootDaemon.readDaemonLog(ctx)

    // ---- browsing ------------------------------------------------------

    /** Order is the browser's business: it sorts by the key the user picked. */
    suspend fun list(path: String): List<EfsEntry> {
        val arr = client.cmd("ls") { put("path", path) }.optJSONArray("entries") ?: return emptyList()
        return (0 until arr.length()).map { EfsEntry.from(arr.getJSONObject(it)) }
    }

    suspend fun stat(path: String): EfsStat = EfsStat.from(client.cmd("stat") { put("path", path) })

    suspend fun statfs(path: String): JSONObject = client.cmd("statfs") { put("path", path) }

    suspend fun readlink(path: String): String =
        client.cmd("readlink") { put("path", path) }.optString("target")

    /** Small files come back inline; anything bigger must be pulled to a file. */
    suspend fun readInline(path: String): ByteArray {
        val res = client.cmd("read") { put("path", path) }
        return Base64.decode(res.optString("data"), Base64.DEFAULT)
    }

    suspend fun pullToCache(path: String): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "pull").apply { mkdirs() }
        val out = File(dir, path.trim('/').replace('/', '_').ifEmpty { "root" })
        out.delete()
        client.cmd("read") {
            put("path", path)
            put("out", out.absolutePath)
        }
        out
    }

    // ---- mutations -----------------------------------------------------

    /** [item] null lets the path decide; true or false forces the type. */
    suspend fun writeFile(
        path: String,
        data: ByteArray,
        mode: Int = 420 /* 0644 */,
        item: Boolean? = null,
    ) =
        withContext(Dispatchers.IO) {
            val dir = File(ctx.cacheDir, "push").apply { mkdirs() }
            val tmp = File(dir, "upload.bin")
            tmp.writeBytes(data)
            tmp.setReadable(true, false)
            try {
                client.cmd("write") {
                    put("path", path)
                    put("src", tmp.absolutePath)
                    put("mode", mode)
                    if (item != null) put("item", item)
                }
            } finally {
                tmp.delete()
            }
            Unit
        }

    suspend fun mkdir(path: String, mode: Int = 511 /* 0777 */) {
        client.cmd("mkdir") { put("path", path); put("mode", mode) }
    }

    suspend fun chmod(path: String, mode: Int) {
        client.cmd("chmod") { put("path", path); put("mode", mode) }
    }

    suspend fun delete(entry: EfsEntry, path: String) {
        val cmd = if (entry.isDir) "rmtree" else "unlink"
        client.cmd(cmd) { put("path", path) }
    }

    /** Path-only delete: always sends `unlink` (file-level, no rmtree dispatch) for callers holding just a path — bulk imports carry no EfsEntry. Matches the Xiaomi mtb tool's delete. */
    suspend fun unlink(path: String) {
        client.cmd("unlink") { put("path", path) }
    }

    suspend fun symlink(target: String, link: String) {
        client.cmd("symlink") { put("target", target); put("link", link) }
    }

    suspend fun sync() {
        client.cmd("sync")
    }

    // ---- backups -------------------------------------------------------

    /** Modem-generated tar of a subtree (EFS2 FS_IMAGE). */
    suspend fun imageBackup(path: String): File = withContext(Dispatchers.IO) {
        val out = File(ctx.cacheDir, "efs-image.tar")
        out.delete()
        client.cmd("image") {
            put("path", path)
            put("out", out.absolutePath)
        }
        out
    }

    /** Recursive copy through the file interface, zipped locally. */
    suspend fun treeBackup(path: String, maxFile: Int = 0): Pair<File, PullSummary> =
        withContext(Dispatchers.IO) {
            val stage = File(ctx.cacheDir, "tree").apply { deleteRecursively(); mkdirs() }
            val res = client.cmd("pull_tree") {
                put("path", path)
                put("out", stage.absolutePath)
                put("max_file", maxFile)
            }
            val errs = res.optJSONArray("error_list")
            val summary = PullSummary(
                dirs = res.optInt("dirs"),
                files = res.optInt("files"),
                links = res.optInt("links"),
                bytes = res.optLong("bytes"),
                errors = res.optInt("errors"),
                errorList = buildList {
                    if (errs != null) for (i in 0 until errs.length()) {
                        val e = errs.getJSONObject(i)
                        add("${e.optString("path")}: ${e.optString("error")}")
                    }
                },
            )

            val zip = File(ctx.cacheDir, "efs-backup.zip")
            zip.delete()
            ZipOutputStream(zip.outputStream().buffered()).use { zos ->
                stage.walkTopDown().filter { it != stage }.forEach { f ->
                    val rel = f.relativeTo(stage).path.replace(File.separatorChar, '/') + if (f.isDirectory) "/" else ""
                    zos.putNextEntry(ZipEntry(rel))
                    if (f.isFile) f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            zip to summary
        }

    suspend fun copyToUri(file: File, uri: Uri) = withContext(Dispatchers.IO) {
        ctx.contentResolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        } ?: throw EfsException("无法打开目标文件进行写入")
        Unit
    }

    suspend fun readUri(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw EfsException("无法读取所选文件")
    }

    /**
     * The name the file picker shows for [uri].
     *
     * The last path segment is not it: a document URI often ends in the
     * provider's own row id ("content://.../document/4068"), which is how a
     * picked `mcfg_autoselect_by_uim` used to arrive as "4068". Only
     * DISPLAY_NAME is the real name, so ask for it and keep the segment as a
     * fallback for providers that do not answer.
     */
    fun displayName(uri: Uri): String {
        val fromProvider = runCatching {
            ctx.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { c ->
                val col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (col >= 0 && c.moveToFirst()) c.getString(col) else null
            }
        }.getOrNull()

        val name = fromProvider?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')

        // A name still has to be usable as a single EFS path component.
        return name?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "file"
    }

    // ---- NV items ------------------------------------------------------

    suspend fun nvRead(item: Int, index: Int? = null): NvResult {
        val res = client.cmd("nv_read") {
            put("item", item)
            if (index != null) put("index", index)
        }
        return NvResult(
            item = res.optInt("item"),
            status = res.optInt("status"),
            statusText = res.optString("status_text"),
            hex = res.optString("data"),
        )
    }

    suspend fun nvWrite(item: Int, hex: String, index: Int? = null) {
        client.cmd("nv_write") {
            put("item", item)
            put("data", hex)
            if (index != null) put("index", index)
        }
    }

    /** Sends the Service Programming Code; true when the modem accepts it. */
    suspend fun spcUnlock(spc: String): Boolean =
        client.cmd("spc") { put("spc", spc) }.optBoolean("unlocked")

    // ---- escape hatch --------------------------------------------------

    suspend fun rawExchange(hex: String): String =
        client.cmd("raw") { put("hex", hex) }.optString("response")

    // ---- modem maintenance ----

    /**
     * Restarts the modem subsystem with the native Qualcomm DIAG request
     * (subsystem 0x25, command 3) the daemon sends over the ordinary DIAG
     * transport. Unlike a vendor QMI service it needs no special endpoint, so
     * it works on any Qualcomm modem.
     *
     * The daemon commits the EFS journal first — an edit that is still only in
     * the journal would be rolled back by the restart — then waits for the
     * modem and rebuilds the session. Returns true when that session came
     * back, false when the modem did not answer in time and a reconnect is
     * needed. Takes several seconds by design.
     */
    suspend fun modemSsr(): Boolean =
        client.cmd("ssr").optBoolean("reconnected", false)
}
