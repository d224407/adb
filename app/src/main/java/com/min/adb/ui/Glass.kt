package com.min.adb.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.RequiresApi
import kotlin.math.abs
import kotlin.math.sqrt
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import com.min.adb.R

// AGSL below is ported from Kyant's Backdrop (rounded-rect SDF, lens refraction, rim light).
private const val SDF = """
float radiusAt(float2 centeredCoord, float4 radii) {
    if (centeredCoord.x >= 0.0) {
        if (centeredCoord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (centeredCoord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        return sign(coord) * normalize(max(cornerCoord, 0.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}"""

private const val LENS = """
uniform shader content;
uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;

$SDF

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(centeredCoord, cornerRadii);

    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);

    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));

    float2 refractedCoord = coord + d * grad;
    return content.eval(refractedCoord);
}"""

private const val LENS_DISPERSION = """
uniform shader content;
uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;
uniform float chromaticAberration;

$SDF

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(centeredCoord, cornerRadii);

    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);

    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));

    float2 refractedCoord = coord + d * grad;
    float dispersionIntensity = chromaticAberration * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));
    float2 dispersedCoord = d * grad * dispersionIntensity;

    half4 color = half4(0.0);

    half4 red = content.eval(refractedCoord + dispersedCoord);
    color.r += red.r / 3.5;
    color.a += red.a / 7.0;

    half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));
    color.r += orange.r / 3.5;
    color.g += orange.g / 7.0;
    color.a += orange.a / 7.0;

    half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));
    color.r += yellow.r / 3.5;
    color.g += yellow.g / 3.5;
    color.a += yellow.a / 7.0;

    half4 green = content.eval(refractedCoord);
    color.g += green.g / 3.5;
    color.a += green.a / 7.0;

    half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));
    color.g += cyan.g / 3.5;
    color.b += cyan.b / 3.0;
    color.a += cyan.a / 7.0;

    half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));
    color.b += blue.b / 3.0;
    color.a += blue.a / 7.0;

    half4 purple = content.eval(refractedCoord - dispersedCoord);
    color.r += purple.r / 7.0;
    color.b += purple.b / 3.0;
    color.a += purple.a / 7.0;

    return color;
}"""

private const val RIM = """
uniform float2 size;
uniform float4 cornerRadii;
layout(color) uniform half4 color;
uniform float angle;
uniform float falloff;

$SDF

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = coord - halfSize;
    float radius = radiusAt(centeredCoord, cornerRadii);

    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);
    float2 normal = float2(cos(angle), sin(angle));
    float d = dot(grad, normal);
    float intensity = pow(abs(d), falloff);
    return color * intensity;
}"""

fun isDarkTheme(ctx: Context): Boolean =
    (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

/** Page container that tells glass surfaces on top of it whenever anything inside it redraws. */
class BackdropLayout(context: Context) : LinearLayout(context) {
    var onChanged: (() -> Unit)? = null
    override fun onDescendantInvalidated(child: View, target: View) {
        super.onDescendantInvalidated(child, target)
        onChanged?.invoke()
    }
}

/**
 * Liquid glass surface: re-draws [backdrop] behind itself with saturation -> blur -> lens refraction,
 * then a tint and a rim light. Blur needs API 31, the lens API 33; below that it is a plain translucent fill.
 */
open class GlassView(
    context: Context,
    private val backdrop: () -> View?,
    private val backdropColor: Int
) : FrameLayout(context) {

    /** Corner radius in px; negative = capsule. */
    var radius = -1f
    var blur = 0f
    var lensHeight = 0f
    var lensAmount = 0f
    var chromatic = false
    var saturation = 1.5f
    var tint = 0
    var overlay = 0
    var rim = 1f

    private val path = Path()
    private val rect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val origin = IntArray(2)
    private val srcOrigin = IntArray(2)
    private var node: Any? = null
    private var lensShader: Any? = null
    private var dispersionShader: Any? = null
    private var rimShader: Any? = null
    private var effectDirty = true
    private var builtW = 0
    private var builtH = 0

    private val redraw = ViewTreeObserver.OnScrollChangedListener { invalidate() }

    init {
        setWillNotDraw(false)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerFor(view.width, view.height))
            }
        }
    }

    /** Re-applies blur/lens/tint changes. */
    fun commit() {
        effectDirty = true
        invalidate()
    }

    private fun cornerFor(w: Int, h: Int): Float {
        val half = minOf(w, h) / 2f
        return if (radius < 0f) half else minOf(radius, half)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnScrollChangedListener(redraw)
    }

    override fun onDetachedFromWindow() {
        runCatching { viewTreeObserver.removeOnScrollChangedListener(redraw) }
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        effectDirty = true
        invalidateOutline()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val r = cornerFor(w, h)
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        path.rewind()
        path.addRoundRect(rect, r, r, Path.Direction.CW)

        val drawn = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && canvas.isHardwareAccelerated &&
            drawBackdrop(canvas, w, h, r)
        if (!drawn) {
            fill.color = (backdropColor and 0xFFFFFF) or 0xE6000000.toInt()
            canvas.drawPath(path, fill)
        }
        fill.color = tint
        canvas.drawPath(path, fill)
        if (overlay != 0) {
            fill.color = overlay
            canvas.drawPath(path, fill)
        }
    }

    override fun onDrawForeground(canvas: Canvas) {
        super.onDrawForeground(canvas)
        if (rim <= 0.01f || width <= 0 || height <= 0) return
        val r = cornerFor(width, height)
        val widthPx = maxOf(1f, resources.displayMetrics.density * 0.75f)
        rimPaint.style = Paint.Style.STROKE
        rimPaint.strokeWidth = widthPx * 2f
        var alpha = 0.5f * rim
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            rimPaint.shader = makeRim(width, height, r)
            rimPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        } else {
            rimPaint.shader = null
            rimPaint.xfermode = null
            rimPaint.color = Color.WHITE
            alpha = 0.3f * rim
        }
        rimPaint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
        canvas.save()
        canvas.clipPath(path)
        canvas.drawPath(path, rimPaint)
        canvas.restore()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun drawBackdrop(canvas: Canvas, w: Int, h: Int, r: Float): Boolean {
        val src = backdrop() ?: return false
        if (!src.isAttachedToWindow) return false
        val n = (node as? RenderNode) ?: RenderNode("glass").also { node = it }
        n.setPosition(0, 0, w, h)
        if (effectDirty || builtW != w || builtH != h) {
            n.setRenderEffect(buildEffect(w, h, r))
            effectDirty = false
            builtW = w
            builtH = h
        }
        getLocationOnScreen(origin)
        src.getLocationOnScreen(srcOrigin)
        val rc = n.beginRecording(w, h)
        try {
            rc.drawColor(backdropColor)
            rc.translate((srcOrigin[0] - origin[0]).toFloat(), (srcOrigin[1] - origin[1]).toFloat())
            src.draw(rc)
        } finally {
            n.endRecording()
        }
        canvas.save()
        canvas.clipPath(path)
        canvas.drawRenderNode(n)
        canvas.restore()
        return true
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun buildEffect(w: Int, h: Int, r: Float): RenderEffect? {
        var e: RenderEffect? = null
        if (saturation != 1f) {
            val m = ColorMatrix().apply { setSaturation(saturation) }
            e = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(m))
        }
        if (blur > 0f) {
            e = if (e == null) RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)
            else RenderEffect.createBlurEffect(blur, blur, e, Shader.TileMode.CLAMP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && lensHeight > 0f && lensAmount > 0f) {
            val lens = lensEffect(w, h, r)
            e = if (e == null) lens else RenderEffect.createChainEffect(lens, e)
        }
        return e
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun lensEffect(w: Int, h: Int, r: Float): RenderEffect {
        val shader: RuntimeShader = if (chromatic) {
            (dispersionShader as? RuntimeShader) ?: RuntimeShader(LENS_DISPERSION).also { dispersionShader = it }
        } else {
            (lensShader as? RuntimeShader) ?: RuntimeShader(LENS).also { lensShader = it }
        }
        shader.setFloatUniform("size", w.toFloat(), h.toFloat())
        shader.setFloatUniform("offset", 0f, 0f)
        shader.setFloatUniform("cornerRadii", floatArrayOf(r, r, r, r))
        shader.setFloatUniform("refractionHeight", lensHeight)
        shader.setFloatUniform("refractionAmount", -lensAmount)
        shader.setFloatUniform("depthEffect", 0f)
        if (chromatic) shader.setFloatUniform("chromaticAberration", 1f)
        return RenderEffect.createRuntimeShaderEffect(shader, "content")
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun makeRim(w: Int, h: Int, r: Float): RuntimeShader {
        val shader = (rimShader as? RuntimeShader) ?: RuntimeShader(RIM).also { rimShader = it }
        shader.setFloatUniform("size", w.toFloat(), h.toFloat())
        shader.setFloatUniform("cornerRadii", floatArrayOf(r, r, r, r))
        shader.setColorUniform("color", Color.WHITE)
        shader.setFloatUniform("angle", (Math.PI / 4.0).toFloat())
        shader.setFloatUniform("falloff", 1f)
        return shader
    }
}


/** Critically/under-damped spring stepped manually (mass 1). */
private class Spring(private val zeta: Float, private val k: Float, var value: Float, private val eps: Float) {
    var target = value
    var vel = 0f
    val active get() = abs(value - target) > eps || abs(vel) > eps * 10f

    fun snap(v: Float) { value = v; target = v; vel = 0f }

    fun step(dt: Float) {
        val c = 2f * zeta * sqrt(k)
        var left = dt
        while (left > 0f) {
            val h = minOf(left, 1f / 240f)
            vel += (-k * (value - target) - c * vel) * h
            value += vel * h
            left -= h
        }
        if (!active) { value = target; vel = 0f }
    }
}

/**
 * Bottom tab bar made of glass (port of Kyant's LiquidBottomTabs): a capsule that can be pressed and dragged,
 * grows with a lens while pressed, squishes with velocity, and the bar bulges and shifts with the drag.
 */
class GlassTabBar(
    context: Context,
    backdrop: () -> View?,
    backdropColor: Int,
    names: List<String>,
    icons: List<Int>,
    private val onSelect: (Int) -> Unit
) : GlassView(context, backdrop, backdropColor) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val tabW = dp(64)
    private val tabH = dp(56)
    private val inset = dp(4)
    private val pressedScale = 64f / 56f
    private val dark = isDarkTheme(context)
    private val container = if (dark) 0x66121212 else 0x66FAFAFA
    private val indicator = GlassView(context, backdrop, backdropColor)
    private val tabs = ArrayList<LinearLayout>()
    private val scroll = HorizontalScrollView(context)
    private var selected = 0

    private val pos = Spring(1f, 1000f, 0f, 0.001f)
    private val press = Spring(1f, 1000f, 0f, 0.001f)
    private val scaleXs = Spring(0.6f, 250f, 1f, 0.001f)
    private val scaleYs = Spring(0.7f, 250f, 1f, 0.001f)
    private val velocity = Spring(0.5f, 300f, 0f, 0.001f)
    private val offset = Spring(0.5f, 300f, 0f, 0.5f)
    private var releasing = false
    private var dragging = false
    private var lastX = 0f
    private var lastPos = 0f
    private var running = false
    private var lastNanos = 0L

    /** Width that fits all tabs, capped to [maxWidth]. */
    fun fitWidth(maxWidth: Int): Int = minOf(tabW * tabs.size + dp(8), maxWidth)

    init {
        radius = -1f
        blur = dp(8).toFloat()
        lensHeight = dp(24).toFloat()
        lensAmount = dp(24).toFloat()
        tint = container
        elevation = dp(10).toFloat()

        indicator.apply {
            blur = dp(8).toFloat()
            tint = container
            rim = 0f
        }

        val strip = FrameLayout(context)
        strip.addView(indicator, FrameLayout.LayoutParams(tabW, tabH).apply { topMargin = inset })
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        names.forEachIndexed { i, name ->
            val tab = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                contentDescription = name
                isClickable = true
                isFocusable = true
                setOnClickListener { if (i != selected) { select(i, true); onSelect(i) } }
                addView(ImageView(context).apply {
                    setImageResource(icons[i])
                    scaleType = ImageView.ScaleType.CENTER
                }, LinearLayout.LayoutParams(dp(24), dp(24)))
                addView(TextView(context).apply {
                    text = name
                    maxLines = 1
                    gravity = Gravity.CENTER
                    TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 8, 11, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                }, LinearLayout.LayoutParams(tabW - dp(6), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
            }
            tabs.add(tab)
            row.addView(tab, LinearLayout.LayoutParams(tabW, tabH))
        }
        strip.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, tabH).apply { topMargin = inset })

        scroll.apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
                }
            }
            addView(strip)
        }
        addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            marginStart = inset; marginEnd = inset
        })
        paintTabs()
        applyState()
    }

    private fun paintTabs() {
        val on = ContextCompat.getColor(context, R.color.md_primary)
        val off = ContextCompat.getColor(context, R.color.md_on_surface)
        tabs.forEachIndexed { i, tab ->
            val c = if (i == selected) on else off
            (tab.getChildAt(0) as ImageView).imageTintList = android.content.res.ColorStateList.valueOf(c)
            (tab.getChildAt(1) as TextView).setTextColor(c)
        }
    }

    /** Moves the capsule to [index]; with [animate] it springs there with the press effect. */
    fun select(index: Int, animate: Boolean) {
        if (index !in tabs.indices) return
        selected = index
        paintTabs()
        if (!animate || !isLaidOut) {
            pos.snap(index.toFloat())
            applyState()
        } else if (pos.target != index.toFloat()) {
            pos.target = index.toFloat()
            pressDown()
            releasing = true
            kick()
        }
        keepVisible(index.toFloat(), true)
    }

    private fun pressDown() {
        press.target = 1f; scaleXs.target = pressedScale; scaleYs.target = pressedScale
    }

    private fun pressUp() {
        press.target = 0f; scaleXs.target = 1f; scaleYs.target = 1f
    }

    private fun keepVisible(p: Float, smooth: Boolean) {
        scroll.post {
            val x = (p * tabW + tabW / 2 - scroll.width / 2).toInt().coerceAtLeast(0)
            if (smooth) scroll.smoothScrollTo(x, 0) else scroll.scrollTo(x, 0)
        }
    }

    // ---- Drag: only the capsule is draggable; the rest of the bar scrolls and taps normally ----
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val x = ev.x - scroll.left + scroll.scrollX
            val left = pos.value * tabW
            if (x >= left && x <= left + tabW) {
                dragging = true
                lastX = ev.x
                return true
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                releasing = false
                pressDown()
                kick()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - lastX
                lastX = ev.x
                pos.target = (pos.target + dx / (tabW * scaleX)).coerceIn(0f, (tabs.size - 1).toFloat())
                offset.snap(offset.value + dx)
                // Keep the capsule inside the visible strip while dragging.
                val x = pos.value * tabW
                if (x < scroll.scrollX) scroll.scrollTo(x.toInt(), 0)
                else if (x + tabW > scroll.scrollX + scroll.width) scroll.scrollTo((x + tabW - scroll.width).toInt(), 0)
                kick()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                val idx = Math.round(pos.target).coerceIn(0, tabs.size - 1)
                pos.target = idx.toFloat()
                offset.target = 0f
                releasing = true
                if (idx != selected) { selected = idx; paintTabs(); onSelect(idx) }
                keepVisible(idx.toFloat(), true)
                kick()
            }
        }
        return true
    }

    // ---- Spring loop ----
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(nanos: Long) {
            val dt = if (lastNanos == 0L) 1f / 60f else ((nanos - lastNanos) / 1e9f).coerceIn(0.001f, 0.05f)
            lastNanos = nanos
            lastPos = pos.value
            pos.step(dt)
            velocity.target = (pos.value - lastPos) / dt / (tabs.size - 1).coerceAtLeast(1)
            velocity.step(dt)
            press.step(dt); scaleXs.step(dt); scaleYs.step(dt); offset.step(dt)
            if (releasing && abs(pos.value - pos.target) < (tabs.size - 1) * 0.025f) { releasing = false; pressUp() }
            applyState()
            if (pos.active || press.active || scaleXs.active || scaleYs.active || velocity.active || offset.active || releasing || dragging) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                running = false
                lastNanos = 0L
            }
        }
    }

    private fun kick() {
        if (running) return
        running = true
        lastNanos = 0L
        Choreographer.getInstance().postFrameCallback(frame)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frame)
        running = false
        super.onDetachedFromWindow()
    }

    private fun applyState() {
        val p = press.value.coerceIn(0f, 1f)
        val w = width.toFloat().takeIf { it > 0f } ?: (tabW * tabs.size).toFloat()
        // Bar: shifts a few dp with the drag and bulges while pressed.
        val f = (offset.value / w).coerceIn(-1f, 1f)
        translationX = dp(4) * Math.signum(f) * (1f - (1f - abs(f)) * (1f - abs(f)))
        val barScale = 1f + dp(16) / w * p
        scaleX = barScale; scaleY = barScale

        indicator.translationX = pos.value * tabW
        val v = velocity.value / 10f
        indicator.scaleX = scaleXs.value / (1f - (v * 0.75f).coerceIn(-0.2f, 0.2f))
        indicator.scaleY = scaleYs.value * (1f - (v * 0.25f).coerceIn(-0.2f, 0.2f))
        indicator.lensHeight = dp(10) * p
        indicator.lensAmount = dp(14) * p
        indicator.chromatic = p > 0.01f
        indicator.rim = p
        val base = if (dark) 0xFFFFFF else 0x000000
        indicator.overlay = (((0x1A * (1f - p)).toInt()) shl 24) or base
        indicator.commit()
        commit()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (changed && !running) { pos.snap(selected.toFloat()); applyState(); keepVisible(selected.toFloat(), false) }
    }
}
