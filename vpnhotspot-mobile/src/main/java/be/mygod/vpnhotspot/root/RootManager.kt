package be.mygod.vpnhotspot.root

import android.annotation.SuppressLint
import android.os.Parcelable
import android.util.Log
import be.mygod.librootkotlinx.AppProcess
import be.mygod.librootkotlinx.Logger
import be.mygod.librootkotlinx.ParcelableThrowable
import be.mygod.librootkotlinx.RootCommandChannel
import be.mygod.librootkotlinx.RootCommandNoResult
import be.mygod.librootkotlinx.RootServer
import be.mygod.librootkotlinx.RootSession
import be.mygod.librootkotlinx.systemContext
import be.mygod.vpnhotspot.App.Companion.app
import be.mygod.vpnhotspot.util.Services
import be.mygod.vpnhotspot.util.UnblockCentral
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import timber.log.Timber

object RootManager : RootSession(), Logger {
    @Volatile
    private var relayLog: ((RemoteLog) -> Unit)? = null
    @Parcelize
    class RootInit : RootCommandNoResult {
        override suspend fun execute() = null.also {
            Timber.plant(object : Timber.DebugTree() {
                @SuppressLint("LogNotTimber")
                override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                    if (priority >= Log.WARN) {
                        Log.println(priority, tag, message)
                    }
                    if (priority >= Log.INFO) relayLog?.invoke(RemoteLog(priority, tag,
                        (if (t == null) message else message.substringBefore('\n')).take(1200),
                        t?.let(::ParcelableThrowable)))
                }
            })
            Logger.me = RootManager
            Services.init { systemContext }
            UnblockCentral.needInit = false
        }
    }
    @Parcelize
    data class RemoteLog(val priority: Int, val tag: String?, val message: String, val error: ParcelableThrowable?) : Parcelable
    @Parcelize
    class RootInitLog : RootCommandChannel<RemoteLog> {
        override fun create(scope: CoroutineScope): ReceiveChannel<RemoteLog> {
            val channel = Channel<RemoteLog>(32, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
            return scope.produce {
                try { channel.consumeEach { send(it) } }
                finally {
                    relayLog = null
                    channel.cancel()
                }
            }.also {
                relayLog = { channel.trySend(it) }
            }
        }
    }

    override fun d(m: String?, t: Throwable?) = Timber.d(t, m)
    override fun e(m: String?, t: Throwable?) = Timber.e(t, m)
    override fun i(m: String?, t: Throwable?) = Timber.i(t, m)
    override fun w(m: String?, t: Throwable?) = Timber.w(t, m)

    override suspend fun initServer(server: RootServer) {
        Logger.me = this
        AppProcess.shouldRelocateHeuristics.let {
            Timber.i("RootManager.relocateEnabled=%s", it)
            server.init(app, it)
        }

        server.execute(RootInit())
        val logs = server.create(RootInitLog(), GlobalScope)
        GlobalScope.launch {
            try {
                logs.consumeEach { (priority, tag, message, error) ->
                    Timber.tag("root/${tag ?: "helper"}").log(priority, error?.unwrap(), message)
                }
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                Timber.w(e)
            }
        }
    }
}
