package com.nvmex.networkhelper.util.modem

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ririxi.bunnylock.data.PrivilegedClient
import dev.ririxi.bunnylock.modem.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration

/** Uses the actual imported controller with fake IPC, never starts su or touches a modem. */
@RunWith(AndroidJUnit4::class)
class BunnyBackendDispatchTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private class FakeHelper(private val vendor: String) : PrivilegedClient {
        override var running = true
        val calls = mutableListOf<Pair<String, JSONObject>>()
        override suspend fun start() = JSONObject().put("uid", 0).put("qrtrLib", true)
        override fun stop() { running = false }
        override suspend fun call(cmd: String, timeout: Duration, build: JSONObject.() -> Unit): JSONObject {
            val request = JSONObject().apply(build)
            calls += cmd to request
            return when (cmd) {
                "detect" -> JSONObject().put("vendor", vendor).put("qrtr", vendor == "qualcomm")
                    .put("pixelRouter", vendor == "tensor").put("mtkOemHook", vendor == "mediatek")
                    .put("aospChannels", true).put("atNodes", JSONArray(listOf("ril:1")))
                "aosp.state" -> JSONObject().put("allowed", Masks.G4 or Masks.G5).put("supportedTypes", Masks.G4 or Masks.G5)
                    .put("channels", JSONArray())
                "qc.state" -> JSONObject().put("hw", JSONObject().put("lte", JSONArray(listOf(1, 3, 8))).put("nr", JSONArray(listOf(41, 78))))
                "mtk.state" -> JSONObject().put("supported", JSONObject().put("lte", JSONArray(listOf(1, 3, 8))).put("nr", JSONArray(listOf(41, 78))))
                "at" -> JSONObject().put("reply", "OK")
                "mtk.cellLock" -> JSONObject().put("active", true)
                else -> JSONObject()
            }
        }
    }

    private suspend fun withModem(vendor: String, access: Access = Access.ROOT,
                                  test: suspend (ModemController, FakeHelper) -> Unit) {
        context.getSharedPreferences("bunnylock", 0).edit().clear().commit()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val helper = FakeHelper(vendor)
        val modem = ModemController(context, scope, HelperStarter { StartedHelper(helper, helper.start(), access) })
        try {
            withTimeout(15_000) { modem.connectAndWait() }
            assertEquals(RootState.Granted, modem.state.value.root)
            assertTrue("refresh should provide groups: ${modem.state.value.log}", modem.state.value.groups.isNotEmpty())
            test(modem, helper)
        } finally { scope.coroutineContext[Job]?.cancelAndJoin(); modem.close() }
    }

    @Test fun autoSelectsAndDispatchesMediatekBandCommandsViaRil() = runBlocking {
        withModem("mediatek", Access.SHIZUKU_ADB) { modem, helper ->
            assertEquals(Backend.MEDIATEK, modem.state.value.backend)
            modem.lockBands(Group.LTE, listOf(3))
            val request = helper.calls.last { it.first == "mtk.setBands" }.second
            assertEquals("ril:1", request.getString("node"))
            assertEquals(3, request.getJSONArray("lte").getInt(0))
            assertFalse(helper.calls.any { it.first.startsWith("qc.") })
        }
    }

    @Test fun pixelBandLockUsesPixelBackendAndRadioCycle() = runBlocking {
        withModem("tensor") { modem, helper ->
            assertEquals(Backend.PIXEL, modem.state.value.backend)
            modem.lockBands(Group.LTE, listOf(3))
            assertTrue(helper.calls.any { it.first == "pixel.setLte" && it.second.getJSONArray("bands").getInt(0) == 3 })
            assertTrue(helper.calls.any { it.first == "radio.cycle" })
        }
    }

    @Test fun adbQualcommFallsBackToHalAndRejectsManualQmi() = runBlocking {
        withModem("qualcomm", Access.SHIZUKU_ADB) { modem, helper ->
            assertEquals(Backend.ANDROID, modem.state.value.backend)
            try { modem.selectBackendAndRefresh(Backend.QUALCOMM); fail("ADB cannot access QRTR") }
            catch (_: IllegalStateException) { }
            assertFalse(helper.calls.any { it.first.startsWith("qc.") })
        }
    }

    @Test fun nrMultiPciPreservesZeroAnd1007AndUsesNrEndpoint() = runBlocking {
        withModem("qualcomm") { modem, helper ->
            modem.lockCells(CellLockRequest(LockRat.NR, nrArfcn = 504990, nrPcis = listOf(0, 1007, 293), nrBand = 41))
            val request = helper.calls.last { it.first == "qc.nrLock" }.second
            assertEquals("multi", request.getString("type"))
            assertEquals("[0,1007,293]", request.getJSONArray("pcis").toString())
            assertEquals(504990L, request.getLong("arfcn"))
            assertFalse(helper.calls.any { it.first == "qc.lteLock" })
        }
    }

    @Test fun atAndRadioControlsReachTheirActualBackendCommands() = runBlocking {
        withModem("mediatek", Access.SHIZUKU_ADB) { modem, helper ->
            modem.setAtNode("ril:1")
            assertEquals("OK", modem.sendAt("AT"))
            modem.radioCycle()
            modem.rebootModem()
            assertEquals("AT", helper.calls.last { it.first == "at" }.second.getString("command"))
            assertTrue(helper.calls.any { it.first == "aosp.rebootModem" })
        }
    }
}
