package com.nvmex.networkhelper.util.map.matching

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import com.amap.api.track.AMapTrackClient
import com.amap.api.track.ErrorCode
import com.amap.api.track.OnTrackLifecycleListener
import com.amap.api.track.TrackParam
import com.amap.api.track.query.entity.DriveMode
import com.amap.api.track.query.model.*
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.ui.map.AmapMapActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Uses the existing Android SDK key. The Web key used to provision the service is never packaged. */
internal class FalconSdkSession(private val context: Context) {
    val sid: Long = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        .metaData?.get("com.nvmex.networkhelper.FALCON_SERVICE_ID")?.toString()?.toLongOrNull() ?: 0L
    private val client: AMapTrackClient by lazy {
        AMapTrackClient.updatePrivacyShow(context, true, true)
        AMapTrackClient.updatePrivacyAgree(context, true)
        AMapTrackClient(context.applicationContext).apply { setInterval(2, 10); setProtocolType(1); setCacheSize(10) }
    }
    private var params: TrackParam? = null
    private var closed = false
    var tid = 0L
        private set
    var trid = 0L
        private set
    var startedAt = 0L
        private set

    suspend fun start() {
        require(sid > 0) { context.getString(R.string.map_falcon_not_configured) }
        check(!closed)
        val prefs = context.getSharedPreferences("falcon_track", Context.MODE_PRIVATE)
        val name = prefs.getString("terminal_name", null) ?: ("networkhelper_" + UUID.randomUUID().toString().replace("-", "")).also {
            prefs.edit().putString("terminal_name", it).apply()
        }
        val existing = checked<QueryTerminalResponse>(request { client.queryTerminal(QueryTerminalRequest(sid, name), it) })
        tid = if (existing.tid > 0) existing.tid else checked<AddTerminalResponse>(request {
            client.addTerminal(AddTerminalRequest(name, sid), it)
        }).tid
        check(tid > 0)
        trid = checked<AddTrackResponse>(request { client.addTrack(AddTrackRequest(sid, tid), it) }).trid
        check(trid > 0 && !closed)
        startedAt = System.currentTimeMillis()
        val param = TrackParam(sid, tid).apply { trackId = trid; notification = notification() }
        params = param
        withTimeout(20_000) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val listener = object : LifecycleAdapter() {
                    override fun onStartTrackCallback(status: Int, msg: String?) {
                        if (closed || !continuation.isActive) return
                        if (status in listOf(ErrorCode.TrackListen.START_TRACK_SUCEE, ErrorCode.TrackListen.START_TRACK_SUCEE_NO_NETWORK,
                                ErrorCode.TrackListen.START_TRACK_ALREADY_STARTED)) client.startGather(this)
                        else continuation.resumeWithException(FalconException(status))
                    }
                    override fun onStartGatherCallback(status: Int, msg: String?) {
                        if (!continuation.isActive) return
                        if (status == ErrorCode.TrackListen.START_GATHER_SUCEE || status == ErrorCode.TrackListen.START_GATHER_ALREADY_STARTED)
                            continuation.resume(Unit)
                        else continuation.resumeWithException(FalconException(status))
                    }
                }
                client.startTrack(param, listener)
            }
        }
    }

    suspend fun query(start: Long = maxOf(startedAt, System.currentTimeMillis() - 180_000), end: Long = System.currentTimeMillis(),
        service: Long = sid, terminal: Long = tid, track: Long = trid, maxPages: Int = 3): FalconTrace {
        val segments = mutableListOf<List<FalconPoint>>()
        for (page in 1..maxPages) {
            val response = checked<QueryTrackResponse>(request { listener ->
                client.queryTerminalTrack(QueryTrackRequest(service, terminal, track, start, end,
                    1, 1, 100, DriveMode.DRIVING, 0, 5000, 1, page, 999), listener)
            })
            var count = 0
            response.tracks.orEmpty().filter { it.trid == track }.forEach { result ->
                val points = result.points.orEmpty().map { FalconPoint(it.lat, it.lng, it.time) }.filter { it.valid }
                count += points.size
                if (points.size >= 2) segments.add(points)
            }
            if (count < 999) break
        }
        return FalconTrace(service, terminal, track, segments)
    }

    fun stop() {
        closed = true
        val param = params ?: return
        params = null
        val listener = LifecycleAdapter()
        runCatching { client.stopGather(listener) }
        runCatching { client.stopTrack(param, listener) }
    }

    private fun notification(): android.app.Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("falcon_correction", context.getString(R.string.map_falcon_notification), NotificationManager.IMPORTANCE_LOW))
        val openMap = PendingIntent.getActivity(context, 0, Intent(context, AmapMapActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, "falcon_correction")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle(context.getString(R.string.map_falcon_notification))
            .setContentText(context.getString(R.string.map_falcon_notification_detail)).setContentIntent(openMap).setOngoing(true).setSilent(true).build()
    }

    private suspend fun request(action: (OnTrackListener) -> Unit): BaseResponse = withTimeout(15_000) {
        suspendCancellableCoroutine { continuation ->
            val listener = object : TrackAdapter() {
                override fun response(value: BaseResponse) { if (continuation.isActive) continuation.resume(value) }
            }
            action(listener)
        }
    }

    private inline fun <reified T : BaseResponse> checked(response: BaseResponse): T {
        if (!response.isSuccess) throw FalconException(response.errorCode)
        return response as? T ?: throw FalconException(-1)
    }

    private open class LifecycleAdapter : OnTrackLifecycleListener {
        override fun onBindServiceCallback(status: Int, msg: String?) = Unit
        override fun onStartGatherCallback(status: Int, msg: String?) = Unit
        override fun onStartTrackCallback(status: Int, msg: String?) = Unit
        override fun onStopGatherCallback(status: Int, msg: String?) = Unit
        override fun onStopTrackCallback(status: Int, msg: String?) = Unit
    }
    private abstract class TrackAdapter : OnTrackListener {
        abstract fun response(value: BaseResponse)
        override fun onQueryTerminalCallback(value: QueryTerminalResponse) = response(value)
        override fun onCreateTerminalCallback(value: AddTerminalResponse) = response(value)
        override fun onDistanceCallback(value: DistanceResponse) = response(value)
        override fun onLatestPointCallback(value: LatestPointResponse) = response(value)
        override fun onHistoryTrackCallback(value: HistoryTrackResponse) = response(value)
        override fun onQueryTrackCallback(value: QueryTrackResponse) = response(value)
        override fun onAddTrackCallback(value: AddTrackResponse) = response(value)
        override fun onParamErrorCallback(value: ParamErrorResponse) = response(value)
    }
}

internal class FalconException(val code: Int) : Exception("${code}")
