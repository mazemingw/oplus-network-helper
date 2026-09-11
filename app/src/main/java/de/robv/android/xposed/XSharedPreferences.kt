package de.robv.android.xposed

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream

class XSharedPreferences(
    private val packageName: String,
    private val prefFileName: String
) {
    private val values = HashMap<String, Any?>()
    private var prefFile: File? = null

    init {
        reload()
    }

    fun reload() {
        values.clear()
        prefFile = locatePrefFile()
        val file = prefFile ?: return
        if (!file.canRead()) return
        runCatching {
            FileInputStream(file).use { input ->
                val parser = Xml.newPullParser()
                parser.setInput(input, "utf-8")
                parsePrefs(parser)
            }
        }
    }

    fun getBoolean(key: String, defValue: Boolean): Boolean {
        return values[key] as? Boolean ?: defValue
    }

    fun getFile(): File = prefFile ?: locatePrefFile() ?: File("")

    private fun locatePrefFile(): File? {
        val xmlName = "$prefFileName.xml"
        val candidates = listOf(
            "/data/user_de/0/$packageName/shared_prefs/$xmlName",
            "/data/user/0/$packageName/shared_prefs/$xmlName",
            "/data/data/$packageName/shared_prefs/$xmlName"
        )
        return candidates.asSequence().map(::File).firstOrNull { it.exists() }
    }

    private fun parsePrefs(parser: XmlPullParser) {
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val tag = parser.name
                val key = parser.getAttributeValue(null, "name")
                if (key != null) {
                    when (tag) {
                        "boolean" -> values[key] = parser.getAttributeValue(null, "value") == "true"
                        "int" -> values[key] = parser.getAttributeValue(null, "value")?.toIntOrNull()
                        "long" -> values[key] = parser.getAttributeValue(null, "value")?.toLongOrNull()
                        "float" -> values[key] = parser.getAttributeValue(null, "value")?.toFloatOrNull()
                        "string" -> values[key] = parser.nextText()
                    }
                }
            }
            event = parser.next()
        }
    }
}

