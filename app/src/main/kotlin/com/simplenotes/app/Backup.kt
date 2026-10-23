package com.simplenotes.app

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

object Backup {
    fun write(ctx: Context, uri: Uri, notes: List<Note>, folders: List<Folder>) {
        val folderNames = folders.associate { it.id to it.name }
        val arr = JSONArray()
        for (n in notes) {
            arr.put(
                JSONObject().apply {
                    put("title", n.title)
                    put("content", n.content)
                    put("color", n.color)
                    put("pinned", n.pinned)
                    put("updated", n.updated)
                    put("archived", n.archived)
                    put("label", n.label)
                    put("checklist", n.checklist)
                    put("reminder", n.reminder)
                    put("locked", n.locked)
                    put("created", n.created)
                    put("fstyle", n.fontStyle)
                    put("fsize", n.fontSize)
                    put("ffamily", n.fontFamily)
                    put("bgcolor", n.bgColor)
                    put("tcolor", n.textColor)
                    put("spans", n.spans)
                    put("rep", n.repeatMin)
                    put("folder", folderNames[n.folder] ?: "")
                }
            )
        }
        val root = JSONObject().put("app", "Notepad").put("version", 3).put("notes", arr)
        val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: error("cannot open file")
        out.use { it.write(root.toString(2).toByteArray(Charsets.UTF_8)) }
    }

    fun restore(ctx: Context, uri: Uri, db: NoteDb): Int {
        val input = ctx.contentResolver.openInputStream(uri) ?: error("cannot open file")
        val text = input.use { it.readBytes().toString(Charsets.UTF_8) }
        val arr = JSONObject(text).getJSONArray("notes")
        var count = 0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val n = Note(
                id = 0L,
                title = o.optString("title"),
                content = o.optString("content"),
                color = o.optInt("color", 0).coerceIn(0, NoteColors.ids.size - 1),
                pinned = o.optBoolean("pinned"),
                updated = o.optLong("updated", System.currentTimeMillis()),
                archived = o.optBoolean("archived"),
                trashedAt = 0L,
                label = o.optString("label"),
                checklist = o.optBoolean("checklist"),
                reminder = o.optLong("reminder"),
                position = 0L,
                locked = o.optBoolean("locked"),
                folder = o.optString("folder").takeIf { it.isNotBlank() }?.let { db.folderId(it) } ?: 0L,
                created = o.optLong("created"),
                fontStyle = o.optInt("fstyle", 0),
                fontSize = o.optInt("fsize", 0),
                fontFamily = o.optInt("ffamily", 0),
                bgColor = o.optInt("bgcolor", 0),
                textColor = o.optInt("tcolor", 0),
                spans = o.optString("spans"),
                repeatMin = o.optInt("rep", 0)
            )
            if (db.exists(n)) continue
            val id = db.save(n)
            if (n.reminder > System.currentTimeMillis()) Reminders.schedule(ctx, id, n.reminder)
            count++
        }
        return count
    }
}
