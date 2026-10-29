package dev.ririxi.bunnylock.modem

import dev.ririxi.bunnylock.data.PrivilegedClient
import org.json.JSONObject

fun interface HelperStarter {
    suspend fun start(showStep: (String) -> Unit): StartedHelper
}

class StartedHelper(
    val client: PrivilegedClient,
    val hello: JSONObject,
    val access: Access,
    val note: String? = null,
)
