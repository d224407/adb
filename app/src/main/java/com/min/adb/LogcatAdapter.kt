package com.min.adb

import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.min.adb.ui.isDarkTheme

/** Log rows in the LogcatReader style: priority badge, tag, message, then date / time / pid / tid. */
class LogcatAdapter(private val onLongClick: (LogEntry) -> Unit) : RecyclerView.Adapter<LogcatAdapter.Holder>() {
    private var items: List<LogEntry> = emptyList()

    init { setHasStableIds(true) }

    fun submit(list: List<LogEntry>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size
    override fun getItemId(position: Int) = items[position].id

    class Holder(root: View, val badge: TextView, val tag: TextView, val msg: TextView, val meta: TextView) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val ctx = parent.context
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        fun text(sp: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
        }
        val onSurface = ContextCompat.getColor(ctx, R.color.md_on_surface)
        val secondary = if (isDarkTheme(ctx)) 0xB2FFFFFF.toInt() else 0x8A000000.toInt()

        val badge = text(12f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(6), 0, dp(6), 0)
        }
        val tag = text(13f, onSurface, bold = true)
        val msg = text(12f, onSurface)
        val meta = text(11f, secondary, bold = true)
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            addView(tag, LinearLayout.LayoutParams(-1, -2))
            addView(msg, LinearLayout.LayoutParams(-1, -2))
            addView(meta, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        }
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(badge, LinearLayout.LayoutParams(-2, -1))
            addView(column, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val divider = View(ctx).apply { setBackgroundColor(ContextCompat.getColor(ctx, R.color.md_outline_variant)) }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(-1, -2)
            addView(body, LinearLayout.LayoutParams(-1, -2))
            addView(divider, LinearLayout.LayoutParams(-1, 1))
        }
        return Holder(root, badge, tag, msg, meta)
    }

    override fun onBindViewHolder(h: Holder, position: Int) {
        val e = items[position]
        h.badge.text = e.priority.toString()
        h.badge.setBackgroundColor(priorityColor(e.priority))
        h.tag.text = e.tag
        h.msg.text = e.msg
        h.meta.text = "${e.date}   ${e.time}   ${e.pid}   ${e.tid}"
        h.itemView.setOnLongClickListener { onLongClick(e); true }
    }

    companion object {
        fun priorityColor(p: Char): Int = when (p) {
            'V' -> 0xFF9E9E9E
            'D' -> 0xFF2196F3
            'I' -> 0xFF4CAF50
            'W' -> 0xFFFF9800
            'E' -> 0xFFF44336
            else -> 0xFF9C27B0 // F, A
        }.toInt()

        const val PRIORITIES = "VDIWEF"
    }
}
