package com.simplenotes.app

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class FolderActivity : BaseActivity() {
    private lateinit var db: NoteDb
    private lateinit var prefs: Prefs
    private lateinit var list: RecyclerView
    private lateinit var adapter: RowAdapter
    private lateinit var touch: ItemTouchHelper

    private var curKind = FolderKind.ALL
    private var curId = 0L
    private var moved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_folders)
        installLock()

        db = NoteDb.of(this)
        prefs = Prefs(this)
        curKind = intent.getIntExtra("kind", FolderKind.ALL)
        curId = intent.getLongExtra("current", 0L)

        list = findViewById(R.id.list)
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(b.left, b.top, b.right, 0)
            list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight, dp(24) + b.bottom)
            insets
        }

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnAdd).setOnClickListener {
            Motion.haptic(it, prefs)
            addFolder()
        }

        adapter = RowAdapter()
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        (list.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        touch = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun isLongPressDragEnabled() = false

            override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int =
                if (adapter.items.getOrNull(vh.bindingAdapterPosition)?.kind == FolderKind.FOLDER)
                    makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
                else 0

            override fun canDropOver(
                rv: RecyclerView, current: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
            ) = adapter.items.getOrNull(target.bindingAdapterPosition)?.kind == FolderKind.FOLDER

            override fun onMove(
                rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
            ): Boolean {
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                adapter.move(from, to)
                moved = true
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(vh, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && vh != null) {
                    Motion.haptic(vh.itemView, prefs, android.view.HapticFeedbackConstants.LONG_PRESS)
                    vh.itemView.animate().scaleX(1.02f).scaleY(1.02f).setDuration(120).start()
                }
            }

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                vh.itemView.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                if (moved) {
                    moved = false
                    db.saveFolderOrder(adapter.items.filter { it.kind == FolderKind.FOLDER }.map { it.id })
                }
            }
        })
        touch.attachToRecyclerView(list)
    }

    override fun onResume() {
        super.onResume()
        if (curKind == FolderKind.FOLDER && db.folderName(curId) == null) {
            curKind = FolderKind.ALL
            curId = 0L
        }
        refresh()
    }

    private fun refresh() {
        val rows = ArrayList<Row>()
        rows += Row(FolderKind.ALL, 0L, getString(R.string.f_all), db.allNotesCount())
        rows += Row(FolderKind.UNCAT, 0L, getString(R.string.folder_uncat), db.uncategorizedCount())
        rows += Row(FolderKind.LOCKED, 0L, getString(R.string.folder_locked), db.lockedCount())
        rows += Row(FolderKind.TRASH, 0L, getString(R.string.folder_trash), db.trashCount())
        db.folders().forEach { rows += Row(FolderKind.FOLDER, it.id, it.name, it.count) }
        adapter.submit(rows)
    }

    private fun choose(r: Row) {
        Motion.haptic(list, prefs)
        setResult(RESULT_OK, Intent().putExtra("kind", r.kind).putExtra("folder", r.id))
        finish()
    }

    private fun addFolder() {
        Folders.createNew(this, db) {
            refresh()
            list.post { list.smoothScrollToPosition(adapter.itemCount - 1) }
        }
    }

    private fun showActions(r: Row) {
        val f = Folder(r.id, r.name, r.count)
        Sheet.show(
            this, f.name, listOf(
                SheetRow(R.drawable.ic_edit, getString(R.string.folder_rename)) {
                    Folders.rename(this, db, f) { refresh() }
                },
                SheetRow(R.drawable.ic_delete, getString(R.string.folder_delete), true) {
                    Folders.delete(this, db, f) {
                        if (curKind == FolderKind.FOLDER && curId == f.id) {
                            curKind = FolderKind.ALL
                            curId = 0L
                        }
                        Toast.makeText(this, R.string.folder_deleted, Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                }
            )
        )
    }

    private class Row(val kind: Int, val id: Long, val name: String, val count: Int)

    private class RowVH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val name: TextView = v.findViewById(R.id.name)
        val count: TextView = v.findViewById(R.id.count)
        val more: ImageButton = v.findViewById(R.id.more)
        val handle: ImageView = v.findViewById(R.id.handle)
        val chevron: ImageView = v.findViewById(R.id.chevron)
    }

    private inner class RowAdapter : RecyclerView.Adapter<RowVH>() {
        val items = ArrayList<Row>()

        init {
            setHasStableIds(true)
        }

        fun submit(rows: List<Row>) {
            items.clear()
            items.addAll(rows)
            notifyDataSetChanged()
        }

        fun move(from: Int, to: Int) {
            items.add(to, items.removeAt(from))
            notifyItemMoved(from, to)
        }

        override fun getItemCount() = items.size

        override fun getItemId(position: Int) =
            items[position].let { if (it.kind == FolderKind.FOLDER) it.id else -(it.kind + 1L) }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowVH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_folder, parent, false)
            v.outlineProvider = null
            v.background = GlassDrawable(this@FolderActivity, resources.getDimension(R.dimen.radius_card))
            val h = RowVH(v)
            v.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) choose(items[p])
            }
            h.more.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) {
                    Motion.haptic(it, prefs)
                    showActions(items[p])
                }
            }
            h.handle.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) touch.startDrag(h)
                false
            }
            return h
        }

        override fun onBindViewHolder(h: RowVH, position: Int) {
            val r = items[position]
            val own = r.kind == FolderKind.FOLDER
            val selected = r.kind == curKind && (!own || r.id == curId)
            val primary = themeColor(androidx.appcompat.R.attr.colorPrimary, Color.BLUE)
            val onSurface = themeColor(com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
            (h.itemView.background as GlassDrawable).fill =
                if (selected) ColorUtils.setAlphaComponent(primary, 0x2E) else Glass.plainFill(this@FolderActivity)
            h.name.text = r.name
            h.name.setTextColor(if (selected) primary else onSurface)
            h.icon.setImageResource(
                when (r.kind) {
                    FolderKind.ALL -> R.drawable.ic_view_list
                    FolderKind.UNCAT -> R.drawable.ic_note
                    FolderKind.LOCKED -> R.drawable.ic_lock
                    FolderKind.TRASH -> R.drawable.ic_delete
                    else -> R.drawable.ic_folder
                }
            )
            h.icon.imageTintList = ColorStateList.valueOf(if (selected) primary else onSurface)
            h.count.text = r.count.toString()
            h.more.visibility = if (own) View.VISIBLE else View.GONE
            h.handle.visibility = if (own) View.VISIBLE else View.GONE
            h.chevron.visibility = if (own) View.GONE else View.VISIBLE
        }
    }
}
