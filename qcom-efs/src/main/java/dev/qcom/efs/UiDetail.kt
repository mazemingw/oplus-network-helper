package dev.qcom.efs

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/* The detail sheet: what an entry is, and what is inside it. */

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun DetailSheet(
    detail: Detail,
    maxInline: Int,
    preview: PreviewData?,
    previewLoading: Boolean,
    previewError: String?,
    readOnly: Boolean,
    onDismiss: () -> Unit,
    onLoadContent: () -> Unit,
    onEdit: () -> Unit,
    onExport: () -> Unit,
    onReplace: () -> Unit,
    onChmod: () -> Unit,
    onDelete: () -> Unit,
) {
    val contentSize = detail.stat?.size ?: detail.entry.size
    val tooLarge = contentSize > maxInline

    // The content is part of the sheet itself: opening the sheet is all it
    // takes to read it.  Files the helper refuses to serve inline are not
    // even tried.
    LaunchedEffect(detail.path) {
        if (!detail.entry.isDir && !tooLarge) onLoadContent()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(detail.entry.name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                if (!detail.entry.isDir) {
                    TextButton(onClick = onExport) {
                        Icon(Icons.Filled.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("导出")
                    }
                }
            }
            SelectionContainer {
                Text(detail.path, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            }

            val st = detail.stat
            KeyValue("类型", efsTypeLabel(detail.entry.type))
            KeyValue("权限", modeOctal(st?.mode ?: detail.entry.mode))
            KeyValue("大小", humanSize(contentSize.toLong()))
            KeyValue("修改时间", time(st?.mtime ?: detail.entry.mtime))
            KeyValue("创建时间", time(st?.ctime ?: detail.entry.ctime))
            st?.target?.let { KeyValue("链接目标", it) }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            if (!detail.entry.isDir) {
                when {
                    previewLoading -> Box(
                        Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                    }
                    tooLarge -> Text(
                        "文件过大，无法在此预览。请点击“导出”保存到手机。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    previewError != null -> Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            previewError,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = onLoadContent) { Text("重试") }
                    }
                    preview != null -> InlinePreview(preview)
                }
            }
            if (!readOnly) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!detail.entry.isDir && !detail.entry.isLink) {
                        OutlinedButton(onClick = onEdit) {
                            Icon(Icons.Filled.Edit, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("编辑")
                        }
                    }
                    if (!detail.entry.isDir) {
                        OutlinedButton(onClick = onReplace) {
                            Icon(Icons.Filled.Upload, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("替换")
                        }
                    }
                    OutlinedButton(onClick = onChmod) { Text("修改权限") }
                    OutlinedButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Icon(Icons.Filled.Delete, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("删除")
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(key, Modifier.width(110.dp), style = MaterialTheme.typography.labelLarge)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun InlinePreview(data: PreviewData) {
    var asText by remember(data) { mutableStateOf(data.asText) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilterChip(selected = asText, onClick = { asText = true }, label = { Text("文本") })
        Spacer(Modifier.width(8.dp))
        FilterChip(selected = !asText, onClick = { asText = false }, label = { Text("十六进制") })
        Spacer(Modifier.weight(1f))
        Text(humanSize(data.bytes.size.toLong()), style = MaterialTheme.typography.labelSmall)
    }
    // Colouring the hex bytes is a value->colour mapping, so it is rebuilt
    // only when the data (or the theme) changes, not on every recomposition.
    // Zero bytes stay in the background: faded grey instead of a colour.
    val dark = isSystemInDarkTheme()
    val zero = LocalContentColor.current.copy(alpha = 0.35f)
    val dump = remember(data, dark, zero) { hexDump(data.bytes, dark, zero) }
    SelectionContainer {
        // Hex rows must not wrap, so they get their own horizontal scroll;
        // text is easier to read wrapped.  Vertical scrolling is the sheet's.
        Text(
            if (asText) AnnotatedString(String(data.bytes, Charsets.ISO_8859_1)) else dump,
            if (asText) Modifier else Modifier.horizontalScroll(rememberScrollState()),
            softWrap = asText,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
    }
}

/**
 * A hex dump where every byte pair is tinted by its value ([byteHue]), so
 * equal bytes show up as equal colours and patterns strike the eye.  The hue
 * is the same on any surface; only the lightness follows the theme, so the
 * pairs stay readable on both light and dark sheets.  Zero bytes are padding
 * rather than data, so they opt out of the colour wheel and render in
 * [zeroColor] -- a faded grey that lets the actual bytes stand out.  Offsets
 * and the ASCII gutter keep the default text colour.
 */
private const val HEX_DIGITS = "0123456789abcdef"

/** Appends [value] as [digits] lowercase hex characters, without String.format. */
private fun AnnotatedString.Builder.appendHex(value: Int, digits: Int) {
    for (shift in (digits - 1) * 4 downTo 0 step 4) append(HEX_DIGITS[(value shr shift) and 0xF])
}

private fun hexDump(
    bytes: ByteArray,
    dark: Boolean,
    zeroColor: Color = Color.Unspecified,
    limit: Int = 8192,
): AnnotatedString =
    buildAnnotatedString {
        val pairLightness = if (dark) 0.74f else 0.38f
        val n = minOf(bytes.size, limit)
        var i = 0
        while (i < n) {
            appendHex(i, 8)
            append("  ")
            for (j in 0 until 16) {
                if (i + j < n) {
                    val b = bytes[i + j]
                    val color =
                        if (b.toInt() == 0) zeroColor
                        else Color.hsl(byteHue(b.toInt()), 0.8f, pairLightness)
                    withStyle(SpanStyle(color = color)) {
                        appendHex(b.toInt() and 0xFF, 2)
                    }
                    append(' ')
                } else {
                    append("   ")
                }
                if (j == 7) append(' ')
            }
            append(" |")
            for (j in 0 until 16) {
                if (i + j >= n) break
                val c = bytes[i + j].toInt() and 0xFF
                append(if (c in 32..126) c.toChar() else '.')
            }
            append("|\n")
            i += 16
        }
        if (bytes.size > limit) append("… 还有 ${bytes.size - limit} 字节未显示\n")
    }
