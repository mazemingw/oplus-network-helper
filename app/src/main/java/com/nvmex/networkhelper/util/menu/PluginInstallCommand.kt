package com.nvmex.networkhelper.util.menu

/** Only app-private plugin ZIPs; never writes system_ext or unpacks framework. */
object PluginInstallCommand {
    fun build(source: String, bytes: Long, target: String): String {
        require(bytes in 1..PluginArchive.MAX_BYTES)
        require(Regex("^/data/user/[0-9]+/com\\.oplus\\.engineernetwork/files/(?:[A-Za-z0-9_-]+/)*plugin-release\\.zip$").matches(target))
        require(source.startsWith('/') && !source.contains('\n') && !source.contains('\r'))
        fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
        val dir = target.substringBeforeLast('/')
        val parent = dir.substringBeforeLast('/')
        val stage = "$target.nh-stage"
        val backup = "$target.nh-backup"
        return "set -e; test -d ${quote(parent)}; mkdir -p ${quote(dir)}; " +
            "nh_owner=\$(stat -c '%u:%g' ${quote(parent)}); printf '%s' \"\$nh_owner\" | grep -Eq '^[0-9]+:[0-9]+$'; " +
            "chown \"\$nh_owner\" ${quote(dir)}; chmod 771 ${quote(dir)}; restorecon -F ${quote(dir)}; " +
            "cp ${quote(source)} ${quote(stage)}; chown \"\$nh_owner\" ${quote(stage)}; chmod 600 ${quote(stage)}; restorecon -F ${quote(stage)}; " +
            "test \"\$(wc -c < ${quote(stage)})\" -eq $bytes; " +
            "if [ -f ${quote(target)} ]; then cp -p ${quote(target)} ${quote(backup)}; restorecon -F ${quote(backup)}; fi; " +
            "mv -f ${quote(stage)} ${quote(target)}; restorecon -F ${quote(target)}"
    }
}
