package com.min.adb.ui

import android.animation.ValueAnimator
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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.RequiresApi
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

/** Bottom tab bar made of glass: a scrolling row of tabs and a glass capsule that slides under the selected one. */
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
    private val dark = isDarkTheme(context)
    private val container = if (dark) 0x66121212 else 0x66FAFAFA
    private val indicator = GlassView(context, backdrop, backdropColor)
    private val tabs = ArrayList<LinearLayout>()
    private val scroll = HorizontalScrollView(context)
    private var selected = 0
    private var press: ValueAnimator? = null

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
            overlay = if (dark) 0x1AFFFFFF else 0x1A000000
            rim = 0f
        }

        val strip = FrameLayout(context)
        strip.addView(indicator, FrameLayout.LayoutParams(tabW, tabH))
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        names.forEachIndexed { i, name ->
            val tab = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                contentDescription = name
                isClickable = true
                isFocusable = true
                setOnClickListener { select(i, true); onSelect(i) }
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
        strip.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, tabH))

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
        addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, tabH, Gravity.CENTER).apply {
            marginStart = dp(4); marginEnd = dp(4)
        })
        paintTabs()
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

    /** Moves the capsule to [index]; with [animate] it overshoots and the lens bulges briefly. */
    fun select(index: Int, animate: Boolean) {
        if (index !in tabs.indices) return
        val changed = index != selected
        selected = index
        paintTabs()
        val target = (index * tabW).toFloat()
        indicator.animate().cancel()
        press?.cancel()
        if (!animate || !isLaidOut) {
            indicator.translationX = target
        } else if (changed) {
            indicator.animate().translationX(target).setDuration(320).setInterpolator(OvershootInterpolator(0.9f)).start()
            press = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
                duration = 420
                addUpdateListener {
                    val p = it.animatedValue as Float
                    indicator.lensHeight = dp(10) * p
                    indicator.lensAmount = dp(14) * p
                    indicator.chromatic = true
                    indicator.rim = p
                    indicator.scaleX = 1f + 0.18f * p
                    indicator.scaleY = 1f + 0.18f * p
                    indicator.commit()
                }
                start()
            }
        }
        scroll.post {
            val x = index * tabW + tabW / 2 - scroll.width / 2
            scroll.smoothScrollTo(x.coerceAtLeast(0), 0)
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (changed && indicator.translationX == 0f && selected != 0) indicator.translationX = (selected * tabW).toFloat()
    }
}
