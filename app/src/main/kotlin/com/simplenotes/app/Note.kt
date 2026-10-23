package com.simplenotes.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.MimeTypeMap
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Note(
    val id: Long = 0L,
    val title: String = "",
    val content: String = "",
    val color: Int = 0,
    val pinned: Boolean = false,
    val updated: Long = 0L,
    val archived: Boolean = false,
    val trashedAt: Long = 0L,
    val label: String = "",
    val checklist: Boolean = false,
    val reminder: Long = 0L,
    val position: Long = 0L,
    val locked: Boolean = false,
    val folder: Long = 0L,
    val attachments: String = "",
    val created: Long = 0L,
    val fontStyle: Int = 0,
    val fontSize: Int = 0,
    val fontFamily: Int = 0,
    val bgColor: Int = 0,
    val textColor: Int = 0,
    val spans: String = "",
    val repeatMin: Int = 0
) {
    fun sameAs(o: Note) = title == o.title && content == o.content && color == o.color &&
        pinned == o.pinned && archived == o.archived && label == o.label &&
        checklist == o.checklist && reminder == o.reminder && locked == o.locked &&
        folder == o.folder && attachments == o.attachments &&
        fontStyle == o.fontStyle && fontSize == o.fontSize &&
        fontFamily == o.fontFamily && bgColor == o.bgColor && textColor == o.textColor && spans == o.spans &&
        repeatMin == o.repeatMin

    fun preview(): String = if (checklist) Checklist.toPreview(content) else content

    fun files(): List<Attachment> = Attachment.parse(attachments)
}

object FontFmt {
    const val MIN = -3
    const val MAX = 8

    val families = arrayOf("sans-serif", "serif", "monospace", "casual", "cursive", "sans-serif-condensed")
    val names = arrayOf("Default", "Serif", "Monospace", "Casual", "Cursive", "Condensed")

    fun scale(step: Int) = 1f + step.coerceIn(MIN, MAX) * 0.1f

    fun typeface(family: Int): android.graphics.Typeface =
        android.graphics.Typeface.create(families[family.coerceIn(0, families.size - 1)], android.graphics.Typeface.NORMAL)

    fun apply(tv: android.widget.TextView, family: Int, step: Int, baseSp: Float) {
        tv.textSize = baseSp * scale(step)
        tv.typeface = typeface(family)
    }
}

object NoteColors {
    val ids = intArrayOf(
        R.color.note_0, R.color.note_1, R.color.note_2,
        R.color.note_3, R.color.note_4, R.color.note_5
    )
}

object Checklist {
    private const val OPEN = "☐ "
    private const val DONE = "☑ "

    fun parse(content: String): List<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>()
        for (raw in content.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("[x] ", true) -> out.add(line.substring(4).trim() to true)
                line.startsWith("[ ] ") -> out.add(line.substring(4).trim() to false)
                line.startsWith(DONE) -> out.add(line.substring(DONE.length).trim() to true)
                line.startsWith(OPEN) -> out.add(line.substring(OPEN.length).trim() to false)
                else -> out.add(line to false)
            }
        }
        return out
    }

    fun encode(rows: List<Pair<String, Boolean>>): String =
        rows.joinToString("\n") { (t, d) -> (if (d) "[x] " else "[ ] ") + t }

    fun toGlyphs(rows: List<Pair<String, Boolean>>): String =
        rows.joinToString("\n") { (t, d) -> (if (d) DONE else OPEN) + t }

    fun toPreview(content: String): String = toGlyphs(parse(content))
}

enum class Filter { ALL, PINNED, CHECKLIST, ARCHIVE, TRASH }

enum class Sort(@StringRes val res: Int) {
    NEWEST(R.string.sort_newest),
    OLDEST(R.string.sort_oldest),
    TITLE(R.string.sort_title),
    COLOR(R.string.sort_color),
    MANUAL(R.string.sort_manual)
}

data class Folder(val id: Long, val name: String, val count: Int)

data class Attachment(val type: Int, val path: String, val name: String) {
    fun file() = File(path)

    fun uri(ctx: Context): Uri =
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file())

    fun duplicate(): Attachment? = try {
        val src = file()
        if (!src.exists()) null
        else {
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val dest = File(src.parentFile, "${System.nanoTime()}_$safe")
            src.copyTo(dest)
            Attachment(type, dest.absolutePath, name)
        }
    } catch (e: Exception) {
        null
    }

    fun mime(): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: when (type) {
                AUDIO -> "audio/*"
                IMAGE -> "image/*"
                else -> "*/*"
            }
    }

    fun thumb(size: Int): Bitmap? {
        if (type != IMAGE || !file().exists()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val AUDIO = 0
        const val IMAGE = 1
        const val FILE = 2

        fun parse(s: String): List<Attachment> {
            if (s.isBlank()) return emptyList()
            return try {
                val arr = JSONArray(s)
                (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    Attachment(o.optInt("t", FILE), o.optString("p"), o.optString("n"))
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

        fun encode(list: List<Attachment>): String {
            if (list.isEmpty()) return ""
            val arr = JSONArray()
            for (a in list) arr.put(JSONObject().put("t", a.type).put("p", a.path).put("n", a.name))
            return arr.toString()
        }

        fun copyFrom(ctx: Context, uri: Uri, type: Int): Attachment? = try {
            var name = "file"
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { name = it }
            }
            val dir = File(ctx.filesDir, "attachments").apply { mkdirs() }
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val dest = File(dir, "${System.nanoTime()}_$safe")
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            } ?: throw IllegalStateException("no stream")
            Attachment(type, dest.absolutePath, name)
        } catch (e: Exception) {
            null
        }
    }
}
