package com.simplenotes.app

import android.content.Context
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object FolderKind {
    const val ALL = 0
    const val UNCAT = 1
    const val LOCKED = 2
    const val TRASH = 3
    const val FOLDER = 4
}

object Folders {
    fun createNew(ctx: Context, db: NoteDb, onCreated: (Long) -> Unit) {
        ctx.nameDialog(R.string.folder_new, "") { name ->
            if (db.folderExists(name)) {
                Toast.makeText(ctx, R.string.folder_exists, Toast.LENGTH_SHORT).show()
            } else {
                onCreated(db.folderId(name))
            }
        }
    }

    fun rename(ctx: Context, db: NoteDb, f: Folder, onDone: () -> Unit) {
        ctx.nameDialog(R.string.folder_rename, f.name) { name ->
            if (db.folderExists(name, f.id)) {
                Toast.makeText(ctx, R.string.folder_exists, Toast.LENGTH_SHORT).show()
            } else {
                db.renameFolder(f.id, name)
                onDone()
            }
        }
    }

    fun delete(ctx: Context, db: NoteDb, f: Folder, onDone: () -> Unit) {
        val n = db.folderNoteCount(f.id)
        val b = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.folder_delete)
            .setNegativeButton(R.string.cancel, null)
        if (n == 0) {
            b.setMessage(ctx.getString(R.string.folder_delete_empty_msg, f.name))
                .setPositiveButton(R.string.folder_delete) { _, _ ->
                    db.deleteFolder(f.id)
                    onDone()
                }
        } else {
            val count = ctx.resources.getQuantityString(R.plurals.n_notes, n, n)
            b.setMessage(ctx.getString(R.string.folder_delete_choose_msg, f.name, count))
                .setPositiveButton(R.string.folder_delete_keep) { _, _ ->
                    db.deleteFolder(f.id, trashNotes = false)
                    onDone()
                }
                .setNeutralButton(R.string.folder_delete_trash) { _, _ ->
                    db.deleteFolder(f.id, trashNotes = true).forEach { Reminders.cancel(ctx, it) }
                    onDone()
                }
        }
        b.show()
    }

    fun create(ctx: Context, db: NoteDb, onCreated: (Long) -> Unit) {
        ctx.nameDialog(R.string.folder_new, "") { name -> onCreated(db.folderId(name)) }
    }

    fun pick(ctx: Context, db: NoteDb, current: Long, onPick: (Long) -> Unit) {
        val folders = db.folders()
        val names = ArrayList<String>()
        names += ctx.getString(R.string.folder_none)
        folders.forEach { names += it.name }
        names += "+  " + ctx.getString(R.string.folder_new)
        val checked = if (current == 0L) 0 else folders.indexOfFirst { it.id == current } + 1
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.move_note)
            .setSingleChoiceItems(names.toTypedArray(), checked) { dlg, which ->
                dlg.dismiss()
                when (which) {
                    0 -> onPick(0L)
                    names.size - 1 -> create(ctx, db, onPick)
                    else -> onPick(folders[which - 1].id)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
