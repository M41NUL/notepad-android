package com.simplenotes.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors

class NoteDb private constructor(context: Context) :
    SQLiteOpenHelper(context, "notes.db", null, 7) {
    companion object {
        private const val COLS =
            "id,title,content,color,pinned,updated,archived,trashed,label,checklist,reminder,position," +
                "locked,folder,attachments,created,fstyle,fsize,ffamily,bgcolor,tcolor,spans,rep"

        @Volatile
        private var inst: NoteDb? = null
        private val io = Executors.newSingleThreadExecutor()
        private val ui = Handler(Looper.getMainLooper())

        fun of(ctx: Context): NoteDb =
            inst ?: synchronized(this) {
                inst ?: NoteDb(ctx.applicationContext).also { inst = it }
            }
    }

    fun bg(work: () -> Unit) {
        io.execute(work)
    }

    fun <T> bgFor(work: () -> T, done: (T) -> Unit) {
        io.execute {
            val r = work()
            ui.post { done(r) }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE notes (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "title TEXT, content TEXT, color INTEGER, " +
                "pinned INTEGER, updated INTEGER, " +
                "archived INTEGER NOT NULL DEFAULT 0, " +
                "trashed INTEGER NOT NULL DEFAULT 0, " +
                "label TEXT NOT NULL DEFAULT '', " +
                "checklist INTEGER NOT NULL DEFAULT 0, " +
                "reminder INTEGER NOT NULL DEFAULT 0, " +
                "position INTEGER NOT NULL DEFAULT 0, " +
                "locked INTEGER NOT NULL DEFAULT 0, " +
                "folder INTEGER NOT NULL DEFAULT 0, " +
                "attachments TEXT NOT NULL DEFAULT '', " +
                "created INTEGER NOT NULL DEFAULT 0, " +
                "fstyle INTEGER NOT NULL DEFAULT 0, " +
                "fsize INTEGER NOT NULL DEFAULT 0, " +
                "ffamily INTEGER NOT NULL DEFAULT 0, " +
                "bgcolor INTEGER NOT NULL DEFAULT 0, " +
                "tcolor INTEGER NOT NULL DEFAULT 0, " +
                "spans TEXT NOT NULL DEFAULT '', " +
                "rep INTEGER NOT NULL DEFAULT 0)"
        )
        createFolders(db)
    }

    private fun createFolders(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE folders (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, " +
                "position INTEGER NOT NULL DEFAULT 0)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE notes ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE notes ADD COLUMN trashed INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE notes ADD COLUMN label TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE notes ADD COLUMN checklist INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE notes ADD COLUMN reminder INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE notes ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE notes SET position = updated")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE notes ADD COLUMN locked INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE notes ADD COLUMN folder INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE notes ADD COLUMN attachments TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE notes ADD COLUMN created INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE notes SET created = updated")
            createFolders(db)
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE notes ADD COLUMN fstyle INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE notes ADD COLUMN fsize INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE notes ADD COLUMN ffamily INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE notes ADD COLUMN bgcolor INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE notes ADD COLUMN tcolor INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE notes ADD COLUMN spans TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE notes ADD COLUMN rep INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 7) {
            db.execSQL("ALTER TABLE folders ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE folders SET position = id")
        }
    }

    private fun read(c: Cursor) = Note(
        id = c.getLong(0),
        title = c.getString(1) ?: "",
        content = c.getString(2) ?: "",
        color = c.getInt(3),
        pinned = c.getInt(4) == 1,
        updated = c.getLong(5),
        archived = c.getInt(6) == 1,
        trashedAt = c.getLong(7),
        label = c.getString(8) ?: "",
        checklist = c.getInt(9) == 1,
        reminder = c.getLong(10),
        position = c.getLong(11),
        locked = c.getInt(12) == 1,
        folder = c.getLong(13),
        attachments = c.getString(14) ?: "",
        created = c.getLong(15),
        fontStyle = c.getInt(16),
        fontSize = c.getInt(17),
        fontFamily = c.getInt(18),
        bgColor = c.getInt(19),
        textColor = c.getInt(20),
        spans = c.getString(21) ?: "",
        repeatMin = c.getInt(22)
    )

    fun query(
        filter: Filter, search: String, label: String?, sort: Sort, folder: Long? = null, lockedOnly: Boolean = false
    ): List<Note> {
        val where = StringBuilder()
        val args = ArrayList<String>()
        when (filter) {
            Filter.TRASH -> where.append("trashed>0")
            Filter.ARCHIVE -> where.append("trashed=0 AND archived=1")
            Filter.PINNED -> where.append("trashed=0 AND archived=0 AND pinned=1")
            Filter.CHECKLIST -> where.append("trashed=0 AND archived=0 AND checklist=1")
            Filter.ALL -> where.append("trashed=0 AND archived=0")
        }
        if (label != null) {
            where.append(" AND label=?")
            args.add(label)
        }
        if (folder != null) {
            where.append(" AND folder=?")
            args.add(folder.toString())
        }
        if (lockedOnly) where.append(" AND locked=1")
        if (search.isNotEmpty()) {
            where.append(" AND (title LIKE ? OR (locked=0 AND content LIKE ?) OR label LIKE ?)")
            repeat(3) { args.add("%$search%") }
        }
        val order = when (sort) {
            Sort.NEWEST -> "updated DESC"
            Sort.OLDEST -> "updated ASC"
            Sort.TITLE -> "LOWER(title) ASC"
            Sort.COLOR -> "color ASC, updated DESC"
            Sort.MANUAL -> "position DESC"
        }
        val list = ArrayList<Note>()
        readableDatabase.rawQuery(
            "SELECT $COLS FROM notes WHERE $where ORDER BY pinned DESC, $order, id DESC",
            args.toTypedArray()
        ).use { c -> while (c.moveToNext()) list.add(read(c)) }
        return list
    }

    fun labels(): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT DISTINCT label FROM notes WHERE label<>'' AND trashed=0 ORDER BY label COLLATE NOCASE",
            null
        ).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        return out
    }

    fun get(id: Long): Note? {
        readableDatabase.rawQuery(
            "SELECT $COLS FROM notes WHERE id=?", arrayOf(id.toString())
        ).use { c -> return if (c.moveToFirst()) read(c) else null }
    }

    fun exportable(): List<Note> {
        val list = ArrayList<Note>()
        readableDatabase.rawQuery(
            "SELECT $COLS FROM notes WHERE trashed=0 ORDER BY updated ASC", null
        ).use { c -> while (c.moveToNext()) list.add(read(c)) }
        return list
    }

    fun pendingReminders(): List<Note> {
        val list = ArrayList<Note>()
        readableDatabase.rawQuery(
            "SELECT $COLS FROM notes WHERE reminder>0 AND trashed=0", null
        ).use { c -> while (c.moveToNext()) list.add(read(c)) }
        return list
    }

    fun exists(n: Note): Boolean {
        readableDatabase.rawQuery(
            "SELECT 1 FROM notes WHERE title=? AND content=? AND updated=? LIMIT 1",
            arrayOf(n.title, n.content, n.updated.toString())
        ).use { c -> return c.moveToFirst() }
    }

    fun save(n: Note): Long {
        val cv = ContentValues().apply {
            if (n.id != 0L) put("id", n.id)
            put("title", n.title)
            put("content", n.content)
            put("color", n.color)
            put("pinned", if (n.pinned) 1 else 0)
            put("updated", n.updated)
            put("archived", if (n.archived) 1 else 0)
            put("trashed", n.trashedAt)
            put("label", n.label)
            put("checklist", if (n.checklist) 1 else 0)
            put("reminder", n.reminder)
            put("position", if (n.position == 0L) System.currentTimeMillis() else n.position)
            put("locked", if (n.locked) 1 else 0)
            put("folder", n.folder)
            put("attachments", n.attachments)
            put("created", if (n.created == 0L) System.currentTimeMillis() else n.created)
            put("fstyle", n.fontStyle)
            put("fsize", n.fontSize)
            put("ffamily", n.fontFamily)
            put("bgcolor", n.bgColor)
            put("tcolor", n.textColor)
            put("spans", n.spans)
            put("rep", n.repeatMin)
        }
        return writableDatabase.insertWithOnConflict(
            "notes", null, cv, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun setTrashed(id: Long, at: Long) {
        writableDatabase.execSQL("UPDATE notes SET trashed=? WHERE id=?", arrayOf<Any>(at, id))
    }

    fun clearReminder(id: Long) {
        writableDatabase.execSQL("UPDATE notes SET reminder=0, rep=0 WHERE id=?", arrayOf<Any>(id))
    }

    fun setReminder(id: Long, at: Long) {
        writableDatabase.execSQL("UPDATE notes SET reminder=? WHERE id=?", arrayOf<Any>(at, id))
    }

    private fun dropFiles(where: String, args: Array<String>?) {
        readableDatabase.rawQuery("SELECT attachments FROM notes WHERE $where AND attachments<>''", args)
            .use { c ->
                while (c.moveToNext()) Attachment.parse(c.getString(0)).forEach { File(it.path).delete() }
            }
    }

    private fun delete(where: String, args: Array<String>?) {
        dropFiles(where, args)
        writableDatabase.delete("notes", where, args)
    }

    fun deleteForever(id: Long) = delete("id=?", arrayOf(id.toString()))

    fun deleteForever(ids: List<Long>) = delete("id IN (${ids.joinToString(",")})", null)

    fun emptyTrash() = delete("trashed>0", null)

    fun purgeTrash(olderThan: Long) = delete("trashed>0 AND trashed<?", arrayOf(olderThan.toString()))

    private fun updateAll(ids: List<Long>, column: String, value: Any) {
        if (ids.isEmpty()) return
        writableDatabase.execSQL(
            "UPDATE notes SET $column=? WHERE id IN (${ids.joinToString(",")})", arrayOf(value)
        )
    }

    fun setPinned(ids: List<Long>, on: Boolean) = updateAll(ids, "pinned", if (on) 1 else 0)

    fun setArchived(ids: List<Long>, on: Boolean) = updateAll(ids, "archived", if (on) 1 else 0)

    fun setLabel(ids: List<Long>, label: String) = updateAll(ids, "label", label)

    fun setTrashed(ids: List<Long>, at: Long) = updateAll(ids, "trashed", at)

    fun moveToFolder(ids: List<Long>, folder: Long) = updateAll(ids, "folder", folder)

    fun folders(): List<Folder> {
        val out = ArrayList<Folder>()
        readableDatabase.rawQuery(
            "SELECT f.id, f.name, " +
                "(SELECT COUNT(*) FROM notes n WHERE n.folder=f.id AND n.trashed=0 AND n.archived=0) " +
                "FROM folders f ORDER BY f.position ASC, f.id ASC", null
        ).use { c -> while (c.moveToNext()) out.add(Folder(c.getLong(0), c.getString(1), c.getInt(2))) }
        return out
    }

    fun folderName(id: Long): String? {
        if (id == 0L) return null
        readableDatabase.rawQuery("SELECT name FROM folders WHERE id=?", arrayOf(id.toString()))
            .use { c -> return if (c.moveToFirst()) c.getString(0) else null }
    }

    fun allNotesCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM notes WHERE trashed=0 AND archived=0", null)
            .use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    fun uncategorizedCount() = count("trashed=0 AND archived=0 AND folder=0")

    fun lockedCount() = count("trashed=0 AND archived=0 AND locked=1")

    fun trashCount() = count("trashed>0")

    private fun count(where: String): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM notes WHERE $where", null)
            .use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    fun folderNoteCount(id: Long): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM notes WHERE folder=? AND trashed=0", arrayOf(id.toString()))
            .use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    fun folderExists(name: String, exceptId: Long = 0L): Boolean {
        readableDatabase.rawQuery(
            "SELECT 1 FROM folders WHERE name=? COLLATE NOCASE AND id<>? LIMIT 1",
            arrayOf(name.trim(), exceptId.toString())
        ).use { c -> return c.moveToFirst() }
    }

    fun folderId(name: String): Long {
        val n = name.trim()
        readableDatabase.rawQuery(
            "SELECT id FROM folders WHERE name=? COLLATE NOCASE", arrayOf(n)
        ).use { c -> if (c.moveToFirst()) return c.getLong(0) }
        var next = 1L
        readableDatabase.rawQuery("SELECT COALESCE(MAX(position), 0) + 1 FROM folders", null)
            .use { c -> if (c.moveToFirst()) next = c.getLong(0) }
        return writableDatabase.insert("folders", null, ContentValues().apply {
            put("name", n)
            put("position", next)
        })
    }

    fun renameFolder(id: Long, name: String) {
        writableDatabase.execSQL("UPDATE folders SET name=? WHERE id=?", arrayOf<Any>(name.trim(), id))
    }

    fun saveFolderOrder(ids: List<Long>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEachIndexed { i, id ->
                db.execSQL("UPDATE folders SET position=? WHERE id=?", arrayOf<Any>(i + 1L, id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deleteFolder(id: Long, trashNotes: Boolean = false): List<Long> {
        val db = writableDatabase
        val trashed = ArrayList<Long>()
        db.beginTransaction()
        try {
            if (trashNotes) {
                db.rawQuery("SELECT id FROM notes WHERE folder=? AND trashed=0", arrayOf(id.toString()))
                    .use { c -> while (c.moveToNext()) trashed.add(c.getLong(0)) }
                db.execSQL(
                    "UPDATE notes SET trashed=? WHERE folder=? AND trashed=0",
                    arrayOf<Any>(System.currentTimeMillis(), id)
                )
            }
            db.execSQL("UPDATE notes SET folder=0 WHERE folder=?", arrayOf<Any>(id))
            db.delete("folders", "id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return trashed
    }

    fun savePositions(ids: List<Long>) {
        val base = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEachIndexed { i, id ->
                db.execSQL("UPDATE notes SET position=? WHERE id=?", arrayOf<Any>(base - i, id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
