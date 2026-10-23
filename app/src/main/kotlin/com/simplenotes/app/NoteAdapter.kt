package com.simplenotes.app

import android.annotation.SuppressLint
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

class NoteAdapter(
    private val prefs: Prefs,
    private val onClick: (Note, View) -> Unit,
    private val onLongClick: (Int) -> Unit,
    private val onHandle: (VH) -> Unit
) : RecyclerView.Adapter<NoteAdapter.VH>() {
    val items = ArrayList<Note>()
    val selected = LinkedHashSet<Long>()
    var selecting = false
        private set
    var showHandle = false
        set(v) {
            if (field != v) {
                field = v
                refresh()
            }
        }
    var folderNames: Map<Long, String> = emptyMap()
        set(v) {
            if (field != v) {
                field = v
                refresh()
            }
        }
    var onSelectionChanged: () -> Unit = {}

    init {
        setHasStableIds(true)
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val card: MaterialCardView = v.findViewById(R.id.card)
        val glass: View = v.findViewById(R.id.glass)
        val title: TextView = v.findViewById(R.id.title)
        val content: TextView = v.findViewById(R.id.content)
        val meta: TextView = v.findViewById(R.id.meta)
        val date: TextView = v.findViewById(R.id.date)
        val folder: TextView = v.findViewById(R.id.folder)
        val pin: ImageView = v.findViewById(R.id.pin)
        val handle: ImageView = v.findViewById(R.id.handle)
        val check: ImageView = v.findViewById(R.id.check)
    }

    override fun getItemId(position: Int) = items[position].id

    override fun getItemCount() = items.size

    fun submit(new: List<Note>, reset: Boolean = false) {
        if (reset) {
            items.clear()
            items.addAll(new)
            notifyDataSetChanged()
            if (selecting && selected.retainAll(new.map { it.id }.toSet())) onSelectionChanged()
            return
        }
        val old = ArrayList(items)
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = new.size
            override fun areItemsTheSame(o: Int, n: Int) = old[o].id == new[n].id
            override fun areContentsTheSame(o: Int, n: Int) = old[o] == new[n]
        })
        items.clear()
        items.addAll(new)
        diff.dispatchUpdatesTo(this)
        if (selecting && selected.retainAll(new.map { it.id }.toSet())) onSelectionChanged()
    }

    fun begin() {
        if (selecting) return
        selecting = true
        refresh()
    }

    fun end() {
        if (!selecting) return
        selecting = false
        selected.clear()
        refresh()
        onSelectionChanged()
    }

    fun isSelected(pos: Int) = items.getOrNull(pos)?.let { it.id in selected } == true

    fun toggle(pos: Int) {
        val id = items.getOrNull(pos)?.id ?: return
        if (!selected.remove(id)) selected.add(id)
        notifyItemChanged(pos, SEL)
        onSelectionChanged()
    }

    fun applyRange(base: Set<Long>, from: Int, to: Int, on: Boolean) {
        val lo = minOf(from, to).coerceAtLeast(0)
        val hi = maxOf(from, to).coerceAtMost(items.lastIndex)
        val range = (lo..hi).map { items[it].id }
        val next = LinkedHashSet(base)
        if (on) next.addAll(range) else next.removeAll(range.toSet())
        if (next == selected) return
        selected.clear()
        selected.addAll(next)
        notifyItemRangeChanged(0, items.size, SEL)
        onSelectionChanged()
    }

    fun selectAll() {
        selected.clear()
        items.forEach { selected.add(it.id) }
        notifyItemRangeChanged(0, items.size, SEL)
        onSelectionChanged()
    }

    fun deselectAll() {
        selected.clear()
        notifyItemRangeChanged(0, items.size, SEL)
        onSelectionChanged()
    }

    private fun bindSelection(h: VH, on: Boolean) {
        val ctx = h.itemView.context
        val density = ctx.resources.displayMetrics.density
        h.check.visibility = if (selecting && on) View.VISIBLE else View.GONE
        h.card.strokeWidth = if (on) (2.5f * density).toInt() else 0
        if (on) h.card.strokeColor = ctx.themeColor(androidx.appcompat.R.attr.colorPrimary)
    }

    fun refresh() {
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size)
    }

    fun move(from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices) return
        items.add(to, items.removeAt(from))
        notifyItemMoved(from, to)
    }

    fun removeAt(pos: Int) {
        if (pos !in items.indices) return
        items.removeAt(pos)
        notifyItemRemoved(pos)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_note, parent, false)
        val h = VH(v)
        h.glass.background = GlassDrawable(parent.context, parent.context.resources.getDimension(R.dimen.radius_card))
        h.card.setOnClickListener { view ->
            val p = h.bindingAdapterPosition
            if (p == RecyclerView.NO_POSITION) return@setOnClickListener
            Motion.haptic(view, prefs)
            if (selecting) toggle(p) else onClick(items[p], h.card)
        }
        h.card.setOnLongClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onLongClick(p)
            true
        }
        h.handle.setOnTouchListener { _, e ->
            if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) onHandle(h)
            false
        }
        Motion.pressable(h.card) { prefs.anim }
        return h
    }

    override fun onBindViewHolder(h: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) bindSelection(h, items[position].id in selected)
        else super.onBindViewHolder(h, position, payloads)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val n = items[position]
        val ctx = h.itemView.context
        val fs = prefs.fontScale
        val solid = if (n.bgColor != 0) n.bgColor else Glass.cardColor(ctx, n.color, prefs.glass)
        (h.glass.background as GlassDrawable).fill = Glass.glassFill(ctx, solid, n.bgColor == 0 && n.color == 0)
        val tc = when {
            n.textColor != 0 -> n.textColor
            n.bgColor != 0 -> Glass.contrastText(n.bgColor)
            else -> ctx.themeColor(com.google.android.material.R.attr.colorOnSurface, android.graphics.Color.BLACK)
        }
        h.title.setTextColor(tc)
        h.content.setTextColor(tc)
        h.date.setTextColor(tc)
        h.title.textSize = 17f * fs
        FontFmt.apply(h.content, n.fontFamily, n.fontSize, 14f * fs)
        h.title.text = n.title
        h.title.visibility = if (n.title.isBlank()) View.GONE else View.VISIBLE
        val preview = if (n.locked) "\uD83D\uDD12  " + ctx.getString(R.string.locked_note) else n.preview()
        if (n.locked) h.content.text = preview
        else {
            val sp = android.text.SpannableString(preview)
            if (!n.checklist && n.spans.isNotBlank()) RichText.apply(sp, n.spans)
            val custom = n.bgColor != 0 || n.textColor != 0
            Links.decorate(sp, if (custom) tc else ctx.themeColor(androidx.appcompat.R.attr.colorPrimary))
            h.content.text = sp
        }
        h.content.visibility = if (preview.isBlank()) View.GONE else View.VISIBLE
        h.pin.visibility = if (n.pinned) View.VISIBLE else View.GONE
        h.handle.visibility = if (showHandle && !selecting) View.VISIBLE else View.GONE
        bindSelection(h, n.id in selected)

        val meta = StringBuilder()
        if (n.label.isNotBlank()) meta.append("# ").append(n.label)
        if (n.reminder > System.currentTimeMillis()) {
            if (meta.isNotEmpty()) meta.append("   ")
            meta.append("\u23F0 ").append(
                DateUtils.formatDateTime(
                    ctx, n.reminder,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL
                )
            )
        }
        val files = n.files().size
        if (files > 0) {
            if (meta.isNotEmpty()) meta.append("   ")
            meta.append("\uD83D\uDCCE ").append(files)
        }
        h.meta.text = meta
        h.meta.visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE

        val fname = if (n.folder != 0L) folderNames[n.folder] else null
        if (fname.isNullOrBlank()) h.folder.visibility = View.GONE
        else {
            val d = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.ic_folder)?.mutate()
            d?.setBounds(0, 0, ctx.dpi(12), ctx.dpi(12))
            d?.setTint(tc)
            h.folder.setCompoundDrawablesRelative(d, null, null, null)
            h.folder.setTextColor(tc)
            h.folder.text = fname
            h.folder.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = ctx.dpi(8).toFloat()
                setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(tc, 30))
            }
            h.folder.visibility = View.VISIBLE
        }

        h.date.text = DateUtils.getRelativeTimeSpanString(
            n.updated, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
        )
    }

    private companion object {
        const val SEL = "sel"
    }
}
