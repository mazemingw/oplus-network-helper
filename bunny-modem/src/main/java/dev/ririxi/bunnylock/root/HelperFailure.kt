package dev.ririxi.bunnylock.root

import androidx.annotation.StringRes
import dev.ririxi.bunnylock.modem.Text
import dev.ririxi.bunnylock.modem.toJson
import dev.ririxi.bunnylock.modem.verbatim

class HelperFailure(
    val text: Text,
) : Exception(debugTag(text))

private fun debugTag(text: Text): String =
    "0x%08x(%s)".format(text.id, text.args.joinToString { if (it is Text) debugTag(it) else it.toString() })

fun fail(
    @StringRes id: Int,
    vararg args: Any,
): Nothing = throw HelperFailure(Text(id, *args))

fun Throwable.toText(): Text = if (this is HelperFailure) text else verbatim(message ?: javaClass.simpleName)

fun Throwable.toTextJson() = toText().toJson()
