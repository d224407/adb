package com.min.adb.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.min.adb.R

/** Material 3 style UI builder based on style.css (colors from res/values[-night]/colors.xml). */
class Ui(val ctx: Context) {

    enum class Pos { SINGLE, TOP, MIDDLE, BOTTOM }

    fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics
    ).toInt()

    fun c(res: Int): Int = ContextCompat.getColor(ctx, res)

    // ------------------------------------------------------------------ background

    private fun shape(fill: Int, radii: FloatArray, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadii = radii
            if (strokeColor != null) setStroke(dp(2), strokeColor)
        }

    private fun radii(tl: Int, tr: Int, br: Int, bl: Int): FloatArray = floatArrayOf(
        dp(tl).toFloat(), dp(tl).toFloat(), dp(tr).toFloat(), dp(tr).toFloat(),
        dp(br).toFloat(), dp(br).toFloat(), dp(bl).toFloat(), dp(bl).toFloat()
    )

    /** Fully rounded (pill) background with a focus outline. */
    fun pillBg(fillRes: Int, radiusDp: Int = 20): StateListDrawable =
        stateBg(fillRes, radii(radiusDp, radiusDp, radiusDp, radiusDp))

    /** Background with a primary outline when focused (TV controller navigation). */
    fun stateBg(fillRes: Int, r: FloatArray): StateListDrawable = StateListDrawable().apply {
        val primary = c(R.color.md_primary)
        addState(intArrayOf(android.R.attr.state_focused), shape(c(fillRes), r, primary))
        addState(intArrayOf(android.R.attr.state_pressed), shape(c(fillRes), r, primary))
        addState(intArrayOf(), shape(c(fillRes), r))
    }

    fun rowBg(pos: Pos, active: Boolean = false): StateListDrawable {
        val big = 24
        val small = 4
        val r = when (pos) {
            Pos.SINGLE -> radii(big, big, big, big)
            Pos.TOP -> radii(big, big, small, small)
            Pos.MIDDLE -> radii(small, small, small, small)
            Pos.BOTTOM -> radii(small, small, big, big)
        }
        return stateBg(if (active) R.color.md_primary_container else R.color.md_surface_container_highest, r)
    }

    // ------------------------------------------------------------------ text

    fun label(
        text: CharSequence,
        sp: Float,
        colorRes: Int,
        bold: Boolean = false,
        mono: Boolean = false
    ): TextView = TextView(ctx).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(c(colorRes))
        typeface = when {
            mono -> Typeface.MONOSPACE
            bold -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            else -> Typeface.SANS_SERIF
        }
    }

    /** .list-title: small, uppercase, primary color. */
    fun listTitle(text: String): TextView = label(text.uppercase(), 14f, R.color.md_primary, bold = true).apply {
        letterSpacing = 0.02f
        setPadding(dp(4), dp(20), dp(4), dp(8))
    }

    // ------------------------------------------------------------------ row

    /** .tweak-row */
    inner class Row(
        name: String,
        desc: String? = null,
        trailing: View? = null,
        trailingExpands: Boolean = false,
        onClick: (() -> Unit)? = null
    ) : LinearLayout(ctx) {
        val nameView = label(name, 16.5f, R.color.md_on_surface, bold = true)
        val descView = label(desc ?: "", 14f, R.color.md_on_surface_variant).apply {
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(2), 0, 0)
            visibility = if (desc.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        private var pos = Pos.SINGLE
        private var active = false

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            minimumHeight = dp(56)
            val info = LinearLayout(ctx).apply {
                orientation = VERTICAL
                addView(nameView)
                addView(descView)
            }
            if (trailingExpands) {
                addView(info, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            } else {
                addView(info, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            if (trailing != null) {
                val trailingParams = if (trailingExpands) {
                    LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                } else {
                    LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                trailingParams.marginStart = dp(14)
                addView(trailing, trailingParams)
            }
            if (onClick != null) {
                isFocusable = true
                isClickable = true
                setOnClickListener { onClick() }
            }
            applyBg()
        }

        fun setPos(p: Pos) { pos = p; applyBg() }

        /** .master-row.active */
        fun setActive(a: Boolean) {
            active = a
            applyBg()
            nameView.setTextColor(c(if (a) R.color.md_on_primary_container else R.color.md_on_surface))
            descView.setTextColor(c(if (a) R.color.md_on_primary_container else R.color.md_on_surface_variant))
        }

        fun setDesc(text: String?) {
            descView.text = text ?: ""
            descView.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        private fun applyBg() { background = rowBg(pos, active) }
    }

    /** .list-container: rows chained together, large corners on the outside, small ones in the middle. */
    fun group(vararg rows: Row): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        rows.forEachIndexed { i, r ->
            r.setPos(
                when {
                    rows.size == 1 -> Pos.SINGLE
                    i == 0 -> Pos.TOP
                    i == rows.size - 1 -> Pos.BOTTOM
                    else -> Pos.MIDDLE
                }
            )
            addView(r, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { if (i > 0) topMargin = dp(2) })
        }
    }

    /** group() from a plain list, for call sites that build Rows dynamically. */
    fun group(rows: List<Row>): LinearLayout = group(*rows.toTypedArray())

    /** Small label chip on the right of a row (shows status/count). */
    fun chip(text: String): TextView = label(text, 13f, R.color.md_on_secondary_container, bold = true).apply {
        setPadding(dp(12), dp(6), dp(12), dp(6))
        val r = dp(14).toFloat()
        background = shape(c(R.color.md_secondary_container), floatArrayOf(r, r, r, r, r, r, r, r))
    }

    // ------------------------------------------------------------------ button

    /** .btn / .btn.primary */
    fun pill(text: String, primary: Boolean = false, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(c(if (primary) R.color.md_on_primary else R.color.md_on_secondary_container))
            minHeight = dp(48)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            val r = dp(24).toFloat()
            background = stateBg(
                if (primary) R.color.md_primary else R.color.md_secondary_container,
                floatArrayOf(r, r, r, r, r, r, r, r)
            )
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
        }

    /** Icon-only pill button (tooltip + content description carry the name). */
    fun iconPill(iconRes: Int, description: String, primary: Boolean = false, onClick: () -> Unit): ImageView =
        ImageView(ctx).apply {
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.CENTER
            imageTintList = ColorStateList.valueOf(c(if (primary) R.color.md_on_primary else R.color.md_on_secondary_container))
            contentDescription = description
            androidx.appcompat.widget.TooltipCompat.setTooltipText(this, description)
            minimumHeight = dp(48)
            val r = dp(24).toFloat()
            background = stateBg(
                if (primary) R.color.md_primary else R.color.md_secondary_container,
                floatArrayOf(r, r, r, r, r, r, r, r)
            )
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
            setOnTouchListener { v, e ->
                when (e.action) {
                    android.view.MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(100).start()
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                }
                false
            }
        }

    /** .action-row */
    fun actionRow(vararg buttons: View): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        buttons.forEachIndexed { i, b ->
            addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = dp(10)
            })
        }
        setPadding(0, dp(12), 0, 0)
    }

    /** .icon-btn */
    fun iconButton(iconRes: Int, onClick: () -> Unit): ImageView = ImageView(ctx).apply {
        setImageResource(iconRes)
        imageTintList = ColorStateList.valueOf(c(R.color.md_on_surface_variant))
        scaleType = ImageView.ScaleType.CENTER
        val r = dp(20).toFloat()
        background = stateBg(R.color.md_surface, floatArrayOf(r, r, r, r, r, r, r, r))
        isFocusable = true
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
    }

    /** .log-filter */
    fun filterChip(text: String, onClick: () -> Unit): TextView = TextView(ctx).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.02f
        setPadding(dp(14), dp(7), dp(14), dp(7))
        isFocusable = true
        isClickable = true
        setOnClickListener { onClick() }
        setSelectedChip(false, false)
    }

    fun TextView.setSelectedChip(selected: Boolean, error: Boolean) {
        val r = dp(20).toFloat()
        val rr = floatArrayOf(r, r, r, r, r, r, r, r)
        val fill = when {
            selected && error -> R.color.md_error_container
            selected -> R.color.md_primary
            else -> R.color.md_surface_container_highest
        }
        val text = when {
            selected && error -> R.color.md_on_error_container
            selected -> R.color.md_on_primary
            else -> R.color.md_on_surface_variant
        }
        background = stateBg(fill, rr)
        setTextColor(c(text))
    }
}

/** .switch: 52x32, 2dp outline, small thumb when off - large when on. */
class M3Switch(private val ui: Ui) : View(ui.ctx) {
    var checked = false
        set(v) {
            field = v
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        isFocusable = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(ui.dp(52), ui.dp(32))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val stroke = ui.dp(2).toFloat()

        paint.style = Paint.Style.FILL
        paint.color = ui.c(if (checked) R.color.md_primary else R.color.md_surface_container_highest)
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, h / 2, h / 2, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = ui.c(if (checked) R.color.md_primary else R.color.md_outline)
        rect.set(stroke / 2, stroke / 2, w - stroke / 2, h - stroke / 2)
        canvas.drawRoundRect(rect, h / 2, h / 2, paint)

        val d = ui.dp(if (checked) 24 else 16).toFloat()
        val left = if (checked) ui.dp(24).toFloat() else ui.dp(4).toFloat()
        paint.style = Paint.Style.FILL
        paint.color = ui.c(if (checked) R.color.md_on_primary else R.color.md_outline)
        canvas.drawCircle(left + d / 2, h / 2, d / 2, paint)
    }
}
