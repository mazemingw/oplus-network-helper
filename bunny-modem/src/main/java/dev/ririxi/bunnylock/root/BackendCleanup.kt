package dev.ririxi.bunnylock.root

import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Root-only host extension. Selection uses actual descriptors AND third-party ownership. */
object BackendCleanup {
    @JvmStatic private external fun scan(): String
    @JvmStatic private external fun release(pid: Int, fd: Int, start: Long, terminate: Boolean): Int

    fun run(request: JSONObject): JSONObject {
        check(Os.getuid() == 0) { "Strong channel cleanup requires Root" }
        val ownUid = request.getInt("hostUid")
        require(ownUid % 100000 >= 10000)
        val user = ownUid / 100000
        val ownApk = request.getString("hostApk")
        val ownPackage = request.getString("hostPackage")
        require(ownPackage.matches(Regex("[A-Za-z0-9_.]+")))
        val installed = Sys.sh("cmd package list packages -3 -f -U --user $user")
        val packages = installed.lineSequence().mapNotNull { line ->
            Regex("^package:(.+)=([A-Za-z0-9_.]+) uid:(\\d+)$").matchEntire(line.trim())?.let {
                Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3].toInt())
            }
        }.filter { it.second != ownPackage && it.third != ownUid }.toList()
        check(packages.isNotEmpty() || installed.isNotBlank()) { "Unable to read third-party package ownership" }
        val report = scan()
        val released = JSONArray(); val protected = JSONArray(); val failures = JSONArray()
        var found = 0
        for (line in report.lineSequence().filter { !it.startsWith('#') && it.isNotBlank() }) {
            val fields = line.split('\t'); if (fields.size != 4) continue
            val pid = fields[0].toInt(); val fd = fields[1].toInt(); val birth = fields[2].toLong(); val uid = fields[3].toInt()
            val proc = File("/proc/$pid")
            val cmd = runCatching { File(proc, "cmdline").readBytes().toString(Charsets.UTF_8).replace('\u0000', ' ').trim() }.getOrDefault("")
            val env = runCatching { File(proc, "environ").readBytes().toString(Charsets.UTF_8).split('\u0000') }.getOrDefault(emptyList())
            val exe = runCatching { Os.readlink("/proc/$pid/exe") }.getOrDefault("")
            val mappedApks = if (uid == 0) runCatching { File(proc, "maps").useLines { lines ->
                lines.map { it.substringAfterLast(' ') }.filter { it.endsWith(".apk") }.toSet()
            } }.getOrDefault(emptySet()) else emptySet()
            val own = uid == ownUid || env.any { it == "CLASSPATH=$ownApk" } ||
                ownApk in mappedApks ||
                exe.startsWith("/data/user/$user/$ownPackage/") || exe.startsWith("/data/data/$ownPackage/")
            val owner = packages.firstOrNull { (apk, _, packageUid) ->
                uid == packageUid || (uid == 0 && (env.any { it == "CLASSPATH=$apk" } || apk in mappedApks))
            }
            // Root native daemons may inherit no APK, but their executable stays in the app's private tree.
            val rootOwner = if (uid == 0) packages.firstOrNull { (_, pkg, _) ->
                exe.startsWith("/data/user/$user/$pkg/") || (user == 0 && exe.startsWith("/data/data/$pkg/"))
            } else null
            val identified = owner ?: rootOwner
            val standalone = uid == 0 && exe.startsWith("/data/local/tmp/")
            if (own || (identified == null && !standalone)) {
                protected.put(JSONObject().put("pid", pid).put("command", cmd)); continue
            }
            found++
            val verified = release(pid, fd, birth, false)
            if (verified != 0) {
                if (proc.exists()) failures.put(JSONObject().put("pid", pid).put("errno", verified).put("stage", "revalidate"))
                continue
            }
            // Prevent application-owned services being recreated; never stop system packages.
            identified?.second?.let { pkg -> Sys.sh("am force-stop --user $user $pkg") }
            val error = release(pid, fd, birth, true)
            val entry = JSONObject().put("pid", pid).put("package", identified?.second).put("command", cmd).put("errno", error)
            if (error == 0 || !proc.exists()) released.put(entry) else failures.put(entry)
        }
        fun flag(name: String) = report.lineSequence().firstOrNull { it.startsWith("#$name\t") }?.substringAfter('\t')?.toIntOrNull() ?: 0
        return JSONObject().put("found", found).put("released", released).put("protected", protected).put("failures", failures)
            .put("denied", flag("denied")).put("unsupported", flag("unsupported") != 0).put("truncated", flag("truncated") != 0)
    }
}
