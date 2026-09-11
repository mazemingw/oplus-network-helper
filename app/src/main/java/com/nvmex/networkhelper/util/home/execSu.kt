package com.nvmex.networkhelper.util.home


 fun execSu(cmd: String): CmdResult {
    return try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        val out = readAll(p.inputStream) + readAll(p.errorStream)
        p.waitFor()
        CmdResult(p.exitValue(), out.trim())
    } catch (e: Exception) {
        CmdResult(-1, "Exception: ${e.message}")
    }
}