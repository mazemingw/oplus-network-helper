package dev.ririxi.bunnylock.modem

import dev.ririxi.bunnylock.data.reasonText
import dev.ririxi.bunnylock.root.Failure
import kotlinx.coroutines.CancellationException

internal fun reasonOf(e: Throwable): Text = (e as? Failure)?.text ?: e.reasonText() ?: Text(R.string.something_went_wrong)

internal fun Text.followedBy(next: Text) = Text(R.string.modem_sentences, this, next)

internal inline fun <T> runSuspendCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
