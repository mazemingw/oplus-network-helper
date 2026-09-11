package com.nvmex.networkhelper.util.home

import java.io.BufferedReader
import java.io.InputStreamReader

 fun readAll(stream: java.io.InputStream): String {
    return try {
        BufferedReader(InputStreamReader(stream)).use { it.readText() }
    } catch (_: Exception) {
        ""
    }
}