package com.simplenotes.app

import android.app.ActivityOptions
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.EditText
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.platform.MaterialContainerTransformSharedElementCallback

private data class Loaded(val notes: List<Note>, val labels: List<String>, val folders: Map<Long, String>)

open class MainActivity : BaseActivity() {
    private lateinit var db: NoteDb
    private lateinit var prefs: Prefs
    private lateinit var adapter: NoteAdapter
    private lateinit var list: RecyclerView
    private lateinit var lm: StaggeredGridLayoutManager
    private lateinit var empty: View
    private lateinit var emptyTitle: TextView
    private lateinit var emptySub: TextView
    private lateinit var search: EditText
    private lateinit var fab: FloatingActionButton
    private lateinit var chips: ChipGroup
    private lateinit var btnView: ImageButton
    private lateinit var navBar: View
    private var insetBottom = 0
    private var insetRight = 0
    private lateinit var titleTv: TextView
    private lateinit var header: View
    private lateinit var selHeader: View
    private lateinit var selCount: TextView
    private lateinit var selBar: LinearLayout
    private lateinit var touchHelper: ItemTouchHelper
    private var pinItem: TextView? = null
    private var archiveItem: TextView? = null

    private val libFilter: Filter? by lazy {
        intent.getStringExtra("filter")?.let { runCatching { Filter.valueOf(it) }.getOrNull() }
    }

    private var filter = Filter.ALL
    private var label: String? = null
    private var folder: Long? = null
    private var lockedOnly = false
    private var loadSeq = 0
    private var animateNext = true
    private var updatingChips = false
    private var dragged = false

    private val handler = Handler(Looper.getMainLooper())
    private val reload = Runnable { load() }

    private val folderPage =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == RESULT_OK) {
                val id = r.data?.getLongExtra("folder", 0L) ?: 0L
                when (r.data?.getIntExtra("kind", FolderKind.ALL) ?: FolderKind.ALL) {
                    FolderKind.UNCAT -> openFolder(0L)
                    FolderKind.LOCKED -> openFolder(null, true)
                    FolderKind.TRASH -> openLibrary(Filter.TRASH)
                    FolderKind.FOLDER -> openFolder(id)
                    else -> openFolder(null)
                }
            }
        }

    private val folderBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = openFolder(null)
    }

    private val backToList = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = endSelection()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (intent.getStringExtra("filter") == null) {
            installSplashScreen().setOnExitAnimationListener { it.remove() }
        }
        setExitSharedElementCallback(MaterialContainerTransformSharedElementCallback())
        window.sharedElementsUseOverlay = false
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        installLock()
        if (savedInstanceState == null && libFilter == null) Splash.show(this)

        db = NoteDb.of(this)
        prefs = Prefs(this)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        emptyTitle = findViewById(R.id.emptyTitle)
        emptySub = findViewById(R.id.emptySub)
        search = findViewById(R.id.search)
        fab = findViewById(R.id.fab)
        chips = findViewById(R.id.chips)
        btnView = findViewById(R.id.btnView)
        navBar = findViewById(R.id.navBar)
        titleTv = findViewById(R.id.title)
        header = findViewById(R.id.header)
        selHeader = findViewById(R.id.selHeader)
        selCount = findViewById(R.id.selCount)
        selBar = findViewById(R.id.selBar)
        filter = libFilter ?: Filter.ALL

        libFilter?.let { f ->
            titleTv.setText(if (f == Filter.TRASH) R.string.f_trash else R.string.f_archive)
            findViewById<View>(R.id.btnBack).apply {
                visibility = View.VISIBLE
                setOnClickListener { finishAfterTransition() }
            }
            findViewById<View>(R.id.chipsScroll).visibility = View.GONE
            navBar.visibility = View.GONE
        }
        if (libFilter == null) {
            findViewById<View>(R.id.btnBack).setOnClickListener {
                Motion.haptic(it, prefs)
                openFolder(null)
            }
        }
        titleTv.pivotX = 0f
        titleTv.post { titleTv.pivotY = titleTv.height / 2f }

        val root = findViewById<View>(R.id.root)
        val content = findViewById<View>(R.id.content)
        val listBottom = dp(100)
        val fabMargin = dp(20)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val b = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            content.setPadding(b.left, b.top, b.right, 0)
            insetBottom = b.bottom
            insetRight = b.right
            navBar.setPadding(0, 0, 0, b.bottom)
            list.updatePadding(bottom = if (libFilter == null) listBottom else b.bottom + listBottom)
            selBar.updateLayoutParams<CoordinatorLayout.LayoutParams> { bottomMargin = dp(12) + b.bottom }
            updateFabMargin()
            insets
        }

        adapter = NoteAdapter(
            prefs,
            onClick = { note, view ->
                when {
                    filter == Filter.TRASH -> trashDialog(note)
                    note.locked -> authenticate({
                        list.post { openEditor(note.id, note.color, view, authed = true, transition = false, bg = note.bgColor) }
                    })
                    else -> openEditor(note.id, note.color, view, bg = note.bgColor)
                }
            },
            onLongClick = { startSelect(it) },
            onHandle = { touchHelper.startDrag(it) }
        )
        adapter.onSelectionChanged = { if (adapter.selecting) updateSelectionUi() }
        lm = StaggeredGridLayoutManager(if (prefs.grid) 2 else 1, StaggeredGridLayoutManager.VERTICAL)
        lm.gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_NONE
        list.layoutManager = lm
        list.adapter = adapter
        (list.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        list.itemAnimator?.apply {
            addDuration = 320
            removeDuration = 260
            moveDuration = 320
        }
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val t = (rv.computeVerticalScrollOffset() / dp(90).toFloat()).coerceIn(0f, 1f)
                val s = 1f - 0.22f * t
                titleTv.scaleX = s
                titleTv.scaleY = s
            }
        })
        touchHelper = ItemTouchHelper(touchCallback)
        touchHelper.attachToRecyclerView(list)
        onBackPressedDispatcher.addCallback(this, folderBack)
        onBackPressedDispatcher.addCallback(this, backToList)

        updateViewIcon()
        btnView.setOnClickListener {
            Motion.haptic(it, prefs)
            prefs.grid = !prefs.grid
            applyGrid()
        }
        setupNav()
        findViewById<View>(R.id.btnSort).setOnClickListener { sortDialog() }
        findViewById<View>(R.id.btnSelClose).setOnClickListener { endSelection() }
        findViewById<View>(R.id.btnSelAll).setOnClickListener { adapter.selectAll() }
        findViewById<View>(R.id.btnDeselect).setOnClickListener { adapter.deselectAll() }
        findViewById<View>(R.id.btnMore).setOnClickListener { showMore() }

        fab.setOnClickListener {
            Motion.haptic(it, prefs)
            openEditor(0L, 0, fab)
        }
        fab.setOnLongClickListener {
            Motion.haptic(it, prefs, HapticFeedbackConstants.LONG_PRESS)
            showQuickActions()
            true
        }
        updateTitle()
        Motion.pressable(fab) { prefs.anim }

        search.afterChanged {
            handler.removeCallbacks(reload)
            handler.postDelayed(reload, 300)
        }

        chips.setOnCheckedStateChangeListener { group, ids ->
            if (updatingChips) return@setOnCheckedStateChangeListener
            val id = ids.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val tag = group.findViewById<Chip>(id)?.tag
            if (tag is String) {
                filter = Filter.ALL
                label = tag
            } else {
                label = null
                filter = when (id) {
                    R.id.chipPinned -> Filter.PINNED
                    R.id.chipLists -> Filter.CHECKLIST
                    else -> Filter.ALL
                }
            }
            Motion.haptic(group, prefs)
            animateNext = true
            load()
        }
    }

    override fun onResume() {
        super.onResume()
        if (folder != null && folder != 0L && db.folderName(folder!!) == null) folder = null
        updateTitle()
        applyPrefs()
        load()
    }

    override fun onDestroy() {
        handler.removeCallbacks(reload)
        super.onDestroy()
    }

    private fun applyPrefs() {
        list.layoutAnimation =
            if (prefs.anim) AnimationUtils.loadLayoutAnimation(this, R.anim.layout_in) else null
        applyGrid()
        adapter.refresh()
    }

    private fun applyGrid() {
        val span = if (prefs.grid) 2 else 1
        if (lm.spanCount != span) {
            lm.spanCount = span
            lm.invalidateSpanAssignments()
        }
        updateViewIcon()
    }

    private fun load() {
        val seq = ++loadSeq
        val f = filter
        val l = label
        val q = search.text.toString().trim()
        val s = prefs.sort
        val fo = folder
        val lo = lockedOnly
        db.bgFor({ Loaded(db.query(f, q, l, s, fo, lo), db.labels(), db.folders().associate { it.id to it.name }) }) { r ->
            if (seq != loadSeq) return@bgFor
            adapter.showHandle = canDrag()
            adapter.folderNames = r.folders
            val headChanged = adapter.items.firstOrNull()?.id != r.notes.firstOrNull()?.id
            val resetGrid = prefs.grid && adapter.itemCount > 0 && headChanged
            adapter.submit(r.notes, reset = resetGrid)
            if (resetGrid) list.scrollToPosition(0)
            updateEmpty()
            refreshLabels(r.labels)
            if (animateNext && r.notes.isNotEmpty() && prefs.anim) {
                animateNext = false
                list.scheduleLayoutAnimation()
            }
        }
    }

    private fun updateEmpty() {
        val isEmpty = adapter.itemCount == 0
        empty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        fab.visibility =
            if (filter == Filter.TRASH || filter == Filter.ARCHIVE || adapter.selecting) View.GONE else View.VISIBLE
        if (!isEmpty) return
        val searching = search.text.isNotBlank() || label != null
        val (t, s) = when {
            searching -> R.string.empty_search_title to R.string.empty_search_sub
            folder != null || lockedOnly -> R.string.empty_folder_title to R.string.empty_folder_sub
            filter == Filter.PINNED -> R.string.empty_pinned_title to R.string.empty_pinned_sub
            filter == Filter.CHECKLIST -> R.string.empty_lists_title to R.string.empty_lists_sub
            filter == Filter.ARCHIVE -> R.string.empty_archive_title to R.string.empty_archive_sub
            filter == Filter.TRASH -> R.string.empty_trash_title to R.string.empty_trash_sub
            else -> R.string.empty_title to R.string.empty_sub
        }
        emptyTitle.setText(t)
        emptySub.setText(s)
    }

    private fun refreshLabels(labels: List<String>) {
        updatingChips = true
        for (i in chips.childCount - 1 downTo 0) {
            if (chips.getChildAt(i).tag is String) chips.removeViewAt(i)
        }
        if (label != null && label !in labels) {
            label = null
            chips.check(R.id.chipAll)
            filter = libFilter ?: Filter.ALL
        }
        for (l in labels) {
            val chip = layoutInflater.inflate(R.layout.chip_label, chips, false) as Chip
            chip.id = View.generateViewId()
            chip.text = l
            chip.tag = l
            chips.addView(chip)
            if (l == label) chip.isChecked = true
        }
        updatingChips = false
    }

    private fun openFolder(id: Long?, locked: Boolean = false) {
        folder = id
        lockedOnly = locked
        animateNext = true
        updateTitle()
        load()
    }

    private fun updateTitle() {
        if (libFilter != null) return
        titleTv.text = when {
            lockedOnly -> getString(R.string.folder_locked)
            folder == 0L -> getString(R.string.folder_uncat)
            else -> folder?.let { db.folderName(it) } ?: getString(R.string.notes)
        }
        val inFolder = folder != null || lockedOnly
        findViewById<View>(R.id.btnBack).visibility = if (inFolder) View.VISIBLE else View.GONE
        folderBack.isEnabled = inFolder
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            titleTv, 14, if (inFolder) 24 else 30, 1, android.util.TypedValue.COMPLEX_UNIT_SP
        )
        updateNav()
    }

    private fun showQuickActions() {
        Sheet.show(
            this, null, listOf(
                SheetRow(R.drawable.ic_note, getString(R.string.qa_text)) { openEditor(0L, 0, fab, mode = "text") },
                SheetRow(R.drawable.ic_checklist, getString(R.string.qa_checklist)) { openEditor(0L, 0, fab, mode = "checklist") },
                SheetRow(R.drawable.ic_mic, getString(R.string.qa_voice)) { openEditor(0L, 0, fab, mode = "voice") },
                SheetRow(R.drawable.ic_edit, getString(R.string.qa_drawing)) { openEditor(0L, 0, fab, mode = "drawing") }
            )
        )
    }

    private fun openEditor(
        id: Long, color: Int, from: View, authed: Boolean = false, transition: Boolean = true, bg: Int = 0,
        mode: String = "text"
    ) {
        for (i in 0 until list.childCount) list.getChildAt(i).transitionName = null
        fab.transitionName = null
        val intent = Intent(this, EditorActivity::class.java)
            .putExtra("id", id).putExtra("color", color).putExtra("bg", bg)
            .putExtra("folder", folder ?: 0L).putExtra("authed", authed).putExtra("mode", mode)
        if (!transition) {
            startActivity(intent)
            return
        }
        from.transitionName = "shared_element_container"
        val options = ActivityOptions.makeSceneTransitionAnimation(this, from, "shared_element_container")
        startActivity(intent, options.toBundle())
    }

    private fun updateFabMargin() {
        val navH = if (navBar.visibility == View.VISIBLE) dp(64) else 0
        val m = dp(20)
        fab.updateLayoutParams<CoordinatorLayout.LayoutParams> {
            bottomMargin = m + navH + insetBottom
            marginEnd = m + insetRight
        }
    }

    private fun setupNav() {
        val notes = findViewById<View>(R.id.navNotes)
        val folders = findViewById<View>(R.id.navFolders)
        val archive = findViewById<View>(R.id.navArchive)
        val settings = findViewById<View>(R.id.navSettings)
        notes.setOnClickListener {
            Motion.haptic(it, prefs)
            if (folder != null || lockedOnly) openFolder(null)
            else if (adapter.itemCount > 0) list.smoothScrollToPosition(0)
        }
        folders.setOnClickListener {
            Motion.haptic(it, prefs)
            val kind = when {
                lockedOnly -> FolderKind.LOCKED
                folder == 0L -> FolderKind.UNCAT
                folder != null -> FolderKind.FOLDER
                else -> FolderKind.ALL
            }
            folderPage.launch(
                Intent(this, FolderActivity::class.java).putExtra("current", folder ?: 0L).putExtra("kind", kind)
            )
        }
        archive.setOnClickListener {
            Motion.haptic(it, prefs)
            openLibrary(Filter.ARCHIVE)
        }
        settings.setOnClickListener {
            Motion.haptic(it, prefs)
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        updateNav()
    }

    private fun updateNav() {
        if (libFilter != null) return
        val primary = themeColor(androidx.appcompat.R.attr.colorPrimary)
        val idle = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
        val inFolder = folder != null || lockedOnly
        fun paint(id: Int, on: Boolean) {
            val c = ColorStateList.valueOf(if (on) primary else idle)
            val v = findViewById<ViewGroup>(id)
            (v.getChildAt(0) as ImageView).imageTintList = c
            (v.getChildAt(1) as TextView).setTextColor(if (on) primary else idle)
        }
        paint(R.id.navNotes, !inFolder)
        paint(R.id.navFolders, inFolder)
        paint(R.id.navArchive, false)
        paint(R.id.navSettings, false)
    }

    private fun openLibrary(f: Filter) {
        startActivity(Intent(this, LibraryActivity::class.java).putExtra("filter", f.name))
    }

    private fun updateViewIcon() {
        btnView.setImageResource(if (prefs.grid) R.drawable.ic_view_list else R.drawable.ic_grid_view)
        btnView.contentDescription = getString(if (prefs.grid) R.string.view_list else R.string.view_grid)
    }

    private fun sortDialog() {
        val sorts = Sort.entries
        val names = sorts.map { getString(it.res) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sort_by)
            .setSingleChoiceItems(names, prefs.sort.ordinal) { dlg, which ->
                prefs.sort = sorts[which]
                dlg.dismiss()
                load()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showMore() {
        val rows = ArrayList<SheetRow>()
        if (filter == Filter.TRASH && adapter.itemCount > 0) {
            rows += SheetRow(R.drawable.ic_delete_forever, getString(R.string.empty_trash), true) {
                emptyTrashDialog()
            }
        }
        if (filter != Filter.TRASH) {
            rows += SheetRow(R.drawable.ic_delete, getString(R.string.f_trash)) { openLibrary(Filter.TRASH) }
        }
        Sheet.show(this, null, rows)
    }

    private fun emptyTrashDialog() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.empty_trash_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete_forever) { _, _ ->
                db.bg { db.emptyTrash() }
                load()
            }.show()
    }

    private fun trashDialog(n: Note) {
        MaterialAlertDialogBuilder(this)
            .setTitle(n.title.ifBlank { getString(R.string.note) })
            .setMessage(R.string.trash_msg)
            .setNeutralButton(R.string.cancel, null)
            .setNegativeButton(R.string.delete_forever) { _, _ ->
                confirmDelete(R.string.confirm_delete_forever, onConfirm = {
                    db.bg { db.deleteForever(n.id) }
                    load()
                })
            }
            .setPositiveButton(R.string.restore) { _, _ ->
                db.bg { db.setTrashed(n.id, 0L) }
                if (n.reminder > System.currentTimeMillis()) Reminders.schedule(this, n.id, n.reminder)
                load()
            }.show()
    }

    private fun startSelect(pos: Int) {
        Motion.haptic(list, prefs, HapticFeedbackConstants.LONG_PRESS)
        if (!adapter.selecting) {
            adapter.begin()
            showSelectionUi(true)
        }
        if (!adapter.isSelected(pos)) adapter.toggle(pos)
    }

    private fun endSelection() {
        adapter.end()
        showSelectionUi(false)
    }

    private fun showSelectionUi(on: Boolean) {
        header.visibility = if (on) View.INVISIBLE else View.VISIBLE
        selHeader.visibility = if (on) View.VISIBLE else View.INVISIBLE
        if (prefs.anim) (if (on) selHeader else header).apply {
            alpha = 0f
            animate().alpha(1f).setDuration(200).start()
        }
        backToList.isEnabled = on
        navBar.visibility = if (on || libFilter != null) View.GONE else View.VISIBLE
        updateFabMargin()
        if (on) {
            buildBar()
            updateSelectionUi()
            selBar.visibility = View.VISIBLE
            selBar.translationY = if (prefs.anim) dp(90).toFloat() else 0f
            if (prefs.anim) selBar.animate().translationY(0f).setDuration(420).setInterpolator(Motion.spring).start()
            fab.visibility = View.GONE
        } else {
            if (prefs.anim) {
                selBar.animate().translationY(dp(110).toFloat()).setDuration(200)
                    .withEndAction { if (!adapter.selecting) selBar.visibility = View.GONE }.start()
            } else selBar.visibility = View.GONE
            updateEmpty()
        }
    }

    private fun selectedNotes(): List<Note> = adapter.items.filter { it.id in adapter.selected }

    private fun updateSelectionUi() {
        val notes = selectedNotes()
        selCount.text = getString(R.string.n_selected, notes.size)
        selBar.alpha = if (notes.isEmpty()) 0.45f else 1f
        val all = notes.isNotEmpty() && notes.size == adapter.itemCount
        findViewById<View>(R.id.btnSelAll).visibility = if (all) View.GONE else View.VISIBLE
        findViewById<View>(R.id.btnDeselect).visibility = if (all) View.VISIBLE else View.GONE
        pinItem?.setText(if (notes.isNotEmpty() && notes.all { it.pinned }) R.string.unpin else R.string.pin)
        archiveItem?.apply {
            val un = notes.isNotEmpty() && notes.all { it.archived }
            setText(if (un) R.string.unarchive else R.string.archive)
            setCompoundDrawablesWithIntrinsicBounds(0, if (un) R.drawable.ic_unarchive else R.drawable.ic_archive, 0, 0)
        }
    }

    private fun barItem(icon: Int, label: Int, red: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        val tint = if (red) ContextCompat.getColor(context, R.color.swipe_bg)
        else themeColor(com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        gravity = android.view.Gravity.CENTER
        setText(label)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            this, 8, 10, 1, android.util.TypedValue.COMPLEX_UNIT_SP
        )
        setTextColor(tint)
        compoundDrawablePadding = dp(3)
        compoundDrawableTintList = ColorStateList.valueOf(tint)
        setCompoundDrawablesWithIntrinsicBounds(0, icon, 0, 0)
        setPadding(dp(2), dp(8), dp(2), dp(6))
        Motion.press(this)
        setOnClickListener {
            if (adapter.selected.isEmpty()) return@setOnClickListener
            Motion.haptic(it, prefs)
            onClick()
        }
    }

    private fun buildBar() {
        selBar.removeAllViews()
        pinItem = null
        archiveItem = null
        if (filter == Filter.TRASH) {
            selBar.addView(barItem(R.drawable.ic_undo, R.string.restore) { bulkRestore() })
            selBar.addView(barItem(R.drawable.ic_delete_forever, R.string.delete_forever, true) { bulkDeleteForever() })
            return
        }
        if (filter == Filter.ARCHIVE) {
            archiveItem = barItem(R.drawable.ic_unarchive, R.string.unarchive) { bulkArchive() }.also { selBar.addView(it) }
            selBar.addView(barItem(R.drawable.ic_delete, R.string.bulk_delete, true) { bulkTrash() })
            return
        }
        selBar.addView(barItem(R.drawable.ic_delete, R.string.bulk_delete, true) { bulkTrash() })
        selBar.addView(barItem(R.drawable.ic_folder, R.string.bulk_move) { bulkMove() })
        selBar.addView(barItem(R.drawable.ic_share, R.string.share) { bulkShare() })
        selBar.addView(barItem(R.drawable.ic_copy, R.string.bulk_duplicate) { bulkDuplicate() })
        pinItem = barItem(R.drawable.ic_pin, R.string.pin) { bulkPin() }.also { selBar.addView(it) }
        selBar.addView(barItem(R.drawable.ic_label, R.string.bulk_label) { bulkLabel() })
        archiveItem = barItem(R.drawable.ic_archive, R.string.archive) { bulkArchive() }.also { selBar.addView(it) }
    }

    private fun bulkDone() {
        endSelection()
        load()
    }

    private fun confirmDelete(msg: Int, onCancel: () -> Unit = {}, onConfirm: () -> Unit) {
        var done = false
        MaterialAlertDialogBuilder(this)
            .setMessage(msg)
            .setNegativeButton(R.string.cancel) { _, _ -> }
            .setPositiveButton(R.string.delete_confirm_btn) { _, _ ->
                done = true
                onConfirm()
            }
            .setOnDismissListener { if (!done) onCancel() }
            .show()
    }

    private fun bulkTrash() {
        val notes = selectedNotes()
        val ids = notes.map { it.id }
        confirmDelete(R.string.confirm_trash) {
            Motion.buzz(this, prefs)
            db.bg { db.setTrashed(ids, System.currentTimeMillis()) }
            notes.forEach { Reminders.cancel(this, it.id) }
            bulkDone()
            Snackbar.make(list, getString(R.string.n_trashed, ids.size), Snackbar.LENGTH_LONG)
                .setAction(R.string.undo) {
                    db.bg { db.setTrashed(ids, 0L) }
                    rescheduleReminders(notes)
                    load()
                }.show()
        }
    }

    private fun bulkRestore() {
        val notes = selectedNotes()
        val ids = notes.map { it.id }
        db.bg { db.setTrashed(ids, 0L) }
        rescheduleReminders(notes)
        bulkDone()
        Snackbar.make(list, getString(R.string.n_restored, ids.size), Snackbar.LENGTH_SHORT).show()
    }

    private fun bulkDeleteForever() {
        val ids = selectedNotes().map { it.id }
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.bulk_forever_confirm, ids.size))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete_forever) { _, _ ->
                Motion.buzz(this, prefs)
                db.bg { db.deleteForever(ids) }
                bulkDone()
            }.show()
    }

    private fun rescheduleReminders(notes: List<Note>) {
        val now = System.currentTimeMillis()
        notes.filter { it.reminder > now }.forEach { Reminders.schedule(this, it.id, it.reminder) }
    }

    private fun bulkMove() {
        val notes = selectedNotes()
        val ids = notes.map { it.id }
        Folders.pick(this, db, notes.map { it.folder }.distinct().singleOrNull() ?: 0L) { id ->
            db.bg { db.moveToFolder(ids, id) }
            val name = db.folderName(id) ?: getString(R.string.folder_none)
            bulkDone()
            Toast.makeText(this, getString(R.string.moved_to, name), Toast.LENGTH_SHORT).show()
        }
    }

    private fun bulkShare() {
        val shareable = selectedNotes().filter { !it.locked }
        if (shareable.isEmpty()) {
            Toast.makeText(this, R.string.locked_not_shared, Toast.LENGTH_SHORT).show()
            return
        }
        val text = shareable.joinToString("\n\n\u2014\u2014\u2014\n\n") { n ->
            listOf(n.title, n.preview()).filter { it.isNotBlank() }.joinToString("\n")
        }
        if (shareable.size < selectedNotes().size) {
            Toast.makeText(this, R.string.locked_not_shared, Toast.LENGTH_SHORT).show()
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share)))
        endSelection()
    }

    private fun bulkDuplicate() {
        val notes = selectedNotes()
        val suffix = getString(R.string.copy_suffix)
        val now = System.currentTimeMillis()
        db.bg {
            notes.forEachIndexed { i, n ->
                db.save(
                    n.copy(
                        id = 0L,
                        title = if (n.title.isBlank()) "" else n.title + suffix,
                        pinned = false,
                        reminder = 0L,
                        repeatMin = 0,
                        trashedAt = 0L,
                        updated = now + i,
                        created = 0L,
                        position = 0L,
                        attachments = Attachment.encode(n.files().mapNotNull { it.duplicate() })
                    )
                )
            }
        }
        bulkDone()
        Toast.makeText(this, getString(R.string.n_duplicated, notes.size), Toast.LENGTH_SHORT).show()
    }

    private fun bulkPin() {
        val notes = selectedNotes()
        val on = !notes.all { it.pinned }
        Motion.pinSound(prefs, on)
        db.bg { db.setPinned(notes.map { it.id }, on) }
        bulkDone()
    }

    private fun bulkLabel() {
        val notes = selectedNotes()
        val ids = notes.map { it.id }
        labelDialog(db, notes.map { it.label }.distinct().singleOrNull() ?: "") { l ->
            db.bg { db.setLabel(ids, l) }
            bulkDone()
        }
    }

    private fun bulkArchive() {
        val notes = selectedNotes()
        val ids = notes.map { it.id }
        val on = !notes.all { it.archived }
        db.bg { db.setArchived(ids, on) }
        bulkDone()
        Snackbar.make(list, getString(if (on) R.string.n_archived else R.string.n_unarchived, ids.size), Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) {
                db.bg { db.setArchived(ids, !on) }
                load()
            }.show()
    }

    private fun canDrag() =
        prefs.sort == Sort.MANUAL && filter == Filter.ALL && label == null && folder == null && !lockedOnly &&
            search.text.isBlank() && !adapter.selecting

    private fun restoreSwiped(pos: Int) {
        adapter.removeAt(pos)
        load()
    }

    private val touchCallback = object : ItemTouchHelper.SimpleCallback(
        0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
    ) {
        override fun getDragDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int =
            if (canDrag()) {
                ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
            } else 0

        override fun isLongPressDragEnabled() = false

        override fun getSwipeDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int =
            if (adapter.selecting) 0 else super.getSwipeDirs(rv, vh)

        override fun getSwipeThreshold(vh: RecyclerView.ViewHolder) = 0.4f

        override fun getAnimationDuration(rv: RecyclerView, type: Int, dx: Float, dy: Float): Long =
            if (!prefs.anim) super.getAnimationDuration(rv, type, dx, dy)
            else if (type == ItemTouchHelper.ANIMATION_TYPE_SWIPE_SUCCESS) 320L else 380L

        override fun onMove(
            rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
        ): Boolean {
            val from = vh.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            adapter.move(from, to)
            dragged = true
            return true
        }

        override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(vh, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && vh != null) {
                Motion.haptic(vh.itemView, prefs, HapticFeedbackConstants.LONG_PRESS)
                vh.itemView.alpha = 0.9f
                if (prefs.anim) vh.itemView.animate().scaleX(1.04f).scaleY(1.04f).setDuration(200)
                    .setInterpolator(Motion.spring).start()
            }
        }

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            vh.itemView.alpha = 1f
            vh.itemView.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(Motion.spring).start()
            if (dragged) {
                dragged = false
                val ids = adapter.items.map { it.id }
                db.bg { db.savePositions(ids) }
            }
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
            val pos = vh.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return
            val note = adapter.items[pos]
            val right = direction == ItemTouchHelper.RIGHT

            when {
                filter == Filter.TRASH && right -> {
                    Motion.haptic(vh.itemView, prefs)
                    adapter.removeAt(pos)
                    updateEmpty()
                    db.bg { db.setTrashed(note.id, 0L) }
                    if (note.reminder > System.currentTimeMillis()) {
                        Reminders.schedule(this@MainActivity, note.id, note.reminder)
                    }
                    load()
                }
                filter == Filter.TRASH -> confirmDelete(R.string.confirm_delete_forever, onCancel = { restoreSwiped(pos) }) {
                    Motion.buzz(this@MainActivity, prefs)
                    adapter.removeAt(pos)
                    updateEmpty()
                    db.bg { db.deleteForever(note.id) }
                    Snackbar.make(list, R.string.note_deleted_forever, Snackbar.LENGTH_LONG)
                        .setAction(R.string.undo) {
                            db.bg { db.save(note) }
                            load()
                        }.show()
                }
                right && filter == Filter.ARCHIVE -> {
                    Motion.haptic(vh.itemView, prefs)
                    adapter.removeAt(pos)
                    updateEmpty()
                    db.bg { db.save(note.copy(archived = false)) }
                    Snackbar.make(list, R.string.unarchived_toast, Snackbar.LENGTH_LONG)
                        .setAction(R.string.undo) {
                            db.bg { db.save(note) }
                            load()
                        }.show()
                }
                right -> {
                    Motion.haptic(vh.itemView, prefs)
                    Motion.pinSound(prefs, !note.pinned)
                    adapter.removeAt(pos)
                    db.bg { db.save(note.copy(pinned = !note.pinned)) }
                    load()
                }
                else -> confirmDelete(R.string.confirm_trash, onCancel = { restoreSwiped(pos) }) {
                    Motion.buzz(this@MainActivity, prefs)
                    adapter.removeAt(pos)
                    updateEmpty()
                    db.bg { db.setTrashed(note.id, System.currentTimeMillis()) }
                    Reminders.cancel(this@MainActivity, note.id)
                    Snackbar.make(list, R.string.note_trashed, Snackbar.LENGTH_LONG)
                        .setAction(R.string.undo) {
                            db.bg { db.setTrashed(note.id, 0L) }
                            if (note.reminder > System.currentTimeMillis()) {
                                Reminders.schedule(this@MainActivity, note.id, note.reminder)
                            }
                            load()
                        }.show()
                }
            }
        }

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onChildDraw(
            c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder,
            dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
        ) {
            if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX != 0f) {
                val v = vh.itemView
                val right = dX > 0
                val reach = (Math.abs(dX) / (v.width * 0.4f)).coerceIn(0f, 1f)
                v.alpha = 1f - 0.4f * (Math.abs(dX) / v.width).coerceIn(0f, 1f)
                val rect = if (right) {
                    RectF(v.left.toFloat(), v.top.toFloat(), v.left + dX, v.bottom.toFloat())
                } else {
                    RectF(v.right + dX, v.top.toFloat(), v.right.toFloat(), v.bottom.toFloat())
                }
                val (colorRes, iconRes) = when {
                    filter == Filter.TRASH && right -> R.color.swipe_restore to R.drawable.ic_undo
                    filter == Filter.TRASH -> R.color.swipe_bg to R.drawable.ic_delete_forever
                    right && filter == Filter.ARCHIVE -> R.color.swipe_restore to R.drawable.ic_archive
                    right -> R.color.swipe_pin to R.drawable.ic_pin
                    else -> R.color.swipe_bg to R.drawable.ic_delete
                }
                bgPaint.color = ContextCompat.getColor(this@MainActivity, colorRes)
                bgPaint.alpha = (Math.abs(dX) / v.width * 3f * 255f).toInt().coerceIn(0, 255)
                val r = dp(22).toFloat()
                c.drawRoundRect(rect, r, r, bgPaint)

                val icon = ContextCompat.getDrawable(this@MainActivity, iconRes)?.mutate()
                if (icon != null && rect.width() > dp(56)) {
                    icon.setTint(Color.WHITE)
                    val size = dp(20) + (dp(10) * Motion.spring.getInterpolation(reach)).toInt()
                    val cx = if (right) (v.left + dp(32)) else (v.right - dp(32))
                    val cy = rect.centerY().toInt()
                    icon.setBounds(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
                    icon.alpha = (Math.abs(dX) / dp(90).toFloat() * 255f).toInt().coerceIn(0, 255)
                    icon.draw(c)
                }
            }
            super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
        }
    }
}
