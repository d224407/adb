package com.min.adb

import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.min.adb.adb.AdbConnection
import com.min.adb.adb.AdbCrypto
import com.min.adb.adb.AdbSyncClient
import com.min.adb.adb.transport.TcpTransport
import com.min.adb.adb.transport.UsbTransport
import com.min.adb.ui.Ui
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** ADB utility with persistent bottom tabs. All remote commands require a user-authorized ADB connection. */
class MainActivity : AppCompatActivity() {
    private lateinit var statusView: TextView
    private lateinit var rootFrame: FrameLayout
    private var toastView: TextView? = null
    private val toastHide = Runnable { dismissToast() }
    private lateinit var ipChip: TextView
    private lateinit var content: FrameLayout
    private lateinit var tabsRow: LinearLayout
    private lateinit var navScroll: HorizontalScrollView
    private lateinit var crypto: AdbCrypto
    private val ui by lazy { Ui(this) }
    private var adb: AdbConnection? = null
    private var selectedTab = TAB_PACKAGES
    private var remoteIp = ""
    private var remotePort = 5555
    private var packageShowSystem = false
    private var packageStateFilter = 0 // 0 = all, 1 = enabled only, 2 = disabled only
    private var packageRawUser = ""
    private var packageRawSystem = ""
    private var processRaw = ""
    private var filePathEdit: EditText? = null
    private var fileListContainer: LinearLayout? = null
    private var fileOutput: TextView? = null
    private var packageFilter: EditText? = null
    private var processFilter: EditText? = null
    private var screenImage: ImageView? = null
    private var screenAuto = false
    private val screenBusy = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private var pendingPushPath: String? = null
    private var pendingPullPath: String? = null

    // Tab order: Packages, Processes, Commands, Remote (D-pad), Screen, Files.
    private val tabNames = listOf("Packages", "Processes", "Commands", "Remote", "Screen", "Files")
    private val tabIcons = listOf(R.drawable.ic_packages, R.drawable.ic_processes, R.drawable.ic_commands, R.drawable.ic_remote, R.drawable.ic_screen, R.drawable.ic_files)

    object KeyEvent {
        const val UP = 19; const val DOWN = 20; const val LEFT = 21; const val RIGHT = 22
        const val CENTER = 23; const val BACK = 4; const val HOME = 3
        const val VOLUME_UP = 24; const val VOLUME_DOWN = 25
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        crypto = AdbCrypto.loadOrGenerate(this)
        buildShellUi()
        renderTab(TAB_PACKAGES)
        // On launch: look for devices on port 5555 and reconnect if the previous one is among them.
        autoReconnect = true
        scanForLastDevice()
    }

    // ---- Auto-reconnect: scan the LAN for :5555 and connect when the last-used IP shows up ----
    private var autoReconnect = false
    private val scanning = AtomicBoolean(false)
    private val rescan = Runnable { scanForLastDevice() }

    private fun scanForLastDevice() {
        handler.removeCallbacks(rescan)
        val last = prefs.getString(PREF_LAST_HOST, null)
        if (!autoReconnect || last.isNullOrBlank() || adb != null || isFinishing || isDestroyed) return
        if (!scanning.compareAndSet(false, true)) return
        setStatus("Looking for $last...")
        var matched = false
        scanForAdbDevices(
            onFound = { ip ->
                if (ip == last && adb == null && !matched) {
                    matched = true
                    remoteIp = ip; remotePort = 5555
                    connectNetwork(ip, 5555)
                }
            },
            onDone = {
                scanning.set(false)
                // Not on the network yet (or just rebooting): keep looking until it is back.
                if (!matched && adb == null && autoReconnect && !isFinishing) handler.postDelayed(rescan, 4000)
            }
        )
    }

    private val prefs by lazy { getSharedPreferences("adb_prefs", MODE_PRIVATE) }

    private fun buildShellUi() {
        // Outer frame: scrollable content behind a floating pill nav bar, same layering as
        // #pages + .nav-bar in style.css (nav bar pinned to the bottom, not pushing content).
        val frame = FrameLayout(this).apply { setBackgroundColor(color(R.color.md_surface)) }
        rootFrame = frame

        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Persistent top bar, shown above every tab: connected device IP (blank if none) +
        // a small Connect button next to it. Both open the same connect/scan/pair dialog.
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(4))
        }
        ipChip = TextView(this).apply {
            textSize = 13.5f
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setTextColor(color(R.color.md_on_surface_variant))
            background = ui.pillBg(R.color.md_surface_container_highest, 200)
            setOnClickListener { openConnectDialog() }
        }.withPressAnimation()
        val connectButton = ImageView(this).apply {
            setImageResource(R.drawable.ic_connect)
            imageTintList = android.content.res.ColorStateList.valueOf(color(R.color.md_on_primary))
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = "Connect"
            background = ui.pillBg(R.color.md_primary, 200)
            isClickable = true; isFocusable = true
            setOnClickListener { openConnectDialog() }
        }.withPressAnimation()
        topBar.addView(ipChip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        topBar.addView(connectButton, LinearLayout.LayoutParams(dp(44), dp(40)))

        statusView = TextView(this).apply {
            text = "Not connected"
            textSize = 13f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(16), dp(2), dp(16), dp(10))
            setTextColor(color(R.color.md_on_surface_variant))
        }

        content = FrameLayout(this)
        page.addView(topBar, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        page.addView(statusView, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        page.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        // .nav-bar: a floating pill, centered, sitting above the content near the bottom.
        navScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            background = ui.pillBg(R.color.md_surface_container_high, 200)
            elevation = dp(6).toFloat()
        }
        tabsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        navScroll.addView(tabsRow)
        tabNames.forEachIndexed { index, label ->
            val item = ImageView(this).apply {
                setImageResource(tabIcons[index])
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = label
                isClickable = true; isFocusable = true
                setOnClickListener { renderTab(index) }
            }
            tabsRow.addView(item, LinearLayout.LayoutParams(dp(56), dp(44)).apply { if (index < tabNames.size - 1) marginEnd = dp(2) })
        }
        frame.addView(page, FrameLayout.LayoutParams(-1, -1))
        frame.addView(
            navScroll,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(16)
            }
        )
        // Rounded-square "+" (sideload APK), floating just above the bottom pill nav bar.
        val fabRadius = dp(18).toFloat()
        val fab = TextView(this).apply {
            text = "+"
            textSize = 30f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(color(R.color.md_on_primary_container))
            background = ui.stateBg(R.color.md_primary_container, FloatArray(8) { fabRadius })
            elevation = dp(6).toFloat()
            isFocusable = true
            isClickable = true
            contentDescription = "Sideload APK"
            setOnClickListener { openSideloadChooser() }
        }.withPressAnimation()
        frame.addView(fab, FrameLayout.LayoutParams(dp(56), dp(56)).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            bottomMargin = dp(16 + 56 + 12)
            marginEnd = dp(16)
        })
        setContentView(frame)
        updateTabHighlight()
        updateConnectionChip()
    }

    private fun updateConnectionChip() {
        if (!::ipChip.isInitialized) return
        ipChip.text = if (adb != null) "$remoteIp:$remotePort" else ""
    }

    private class PageRefs(
        val filePathEdit: EditText?, val fileListContainer: LinearLayout?, val fileOutput: TextView?,
        val packageFilter: EditText?, val processFilter: EditText?, val screenImage: ImageView?
    )
    private fun saveRefs() = PageRefs(filePathEdit, fileListContainer, fileOutput, packageFilter, processFilter, screenImage)
    private fun restoreRefs(r: PageRefs) {
        filePathEdit = r.filePathEdit; fileListContainer = r.fileListContainer; fileOutput = r.fileOutput
        packageFilter = r.packageFilter; processFilter = r.processFilter; screenImage = r.screenImage
    }
    private fun clearRefs() { filePathEdit = null; fileListContainer = null; fileOutput = null; packageFilter = null; processFilter = null; screenImage = null }

    private var currentPage: ScrollView? = null
    private val slideInterpolator = PathInterpolator(0.3f, 0f, 0.2f, 1f)

    /** Builds one tab's page. Leaves that page's view refs in the member fields. */
    private fun buildPage(index: Int): ScrollView {
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Extra bottom padding so content can scroll clear of the floating pill nav bar.
            setPadding(dp(16), dp(14), dp(16), dp(170))
        }
        scroll.addView(body)
        when (index) {
            TAB_PACKAGES -> buildPackagesTab(body)
            TAB_PROCESSES -> buildProcessesTab(body)
            TAB_COMMANDS -> buildCommandsTab(body)
            TAB_REMOTE -> buildRemoteTab(body)
            TAB_SCREEN -> buildScreenTab(body)
            TAB_FILES -> buildFilesTab(body)
        }
        return scroll
    }

    /** Drops every page except the current one and stops any running slide. */
    private fun settlePages() {
        for (i in content.childCount - 1 downTo 0) {
            val v = content.getChildAt(i)
            v.animate().cancel()
            if (v !== currentPage) content.removeViewAt(i)
        }
    }

    private fun renderTab(index: Int) {
        cancelDrag()
        settlePages()
        val previous = selectedTab
        val old = currentPage
        selectedTab = index
        updateTabHighlight()
        clearRefs()
        val page = buildPage(index)
        content.addView(page)
        currentPage = page
        if (old != null) {
            val w = content.width.toFloat()
            if (index != previous && w > 0f) {
                // Slides through the tabs in between: distance = number of tabs crossed, so the
                // duration grows with it (same speed whether you hop one tab or four).
                val dir = if (index > previous) 1 else -1
                val dist = Math.abs(index - previous)
                val duration = (200L * dist).coerceIn(200L, 800L)
                page.translationX = dir * w * dist
                old.animate().translationX(-dir * w * dist).setDuration(duration).setInterpolator(slideInterpolator)
                    .withEndAction { content.removeView(old) }.start()
                page.animate().translationX(0f).setDuration(duration).setInterpolator(slideInterpolator).start()
            } else content.removeView(old)
        }
        if (index == TAB_SCREEN && screenAuto) handler.post { scheduleScreenCapture() }
    }

    // ---- Finger-following swipe between neighbouring tabs ----
    private var dragTracking = false
    private var dragging = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragDir = 0 // +1: heading to the next tab (page moves left), -1: previous
    private var dragNeighbor: ScrollView? = null
    private var dragNeighborRefs: PageRefs? = null
    private var dragCurrentRefs: PageRefs? = null
    private var velocity: android.view.VelocityTracker? = null

    private fun cancelDrag() {
        if (!dragging && !dragTracking) return
        dragNeighbor?.let { content.removeView(it) }
        currentPage?.translationX = 0f
        dragCurrentRefs?.let { restoreRefs(it) }
        dragNeighbor = null; dragNeighborRefs = null; dragCurrentRefs = null
        dragging = false; dragTracking = false
        velocity?.recycle(); velocity = null
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!::navScroll.isInitialized || !::content.isInitialized) return super.dispatchTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val loc = IntArray(2)
                content.getLocationOnScreen(loc)
                val inContent = ev.rawY >= loc[1] && ev.rawY < loc[1] + content.height
                val nav = IntArray(2); navScroll.getLocationOnScreen(nav)
                dragTracking = inContent && ev.rawY < nav[1] && content.width > 0
                dragging = false
                dragStartX = ev.rawX; dragStartY = ev.rawY
                velocity?.recycle(); velocity = android.view.VelocityTracker.obtain()
                velocity?.addMovement(ev)
            }
            MotionEvent.ACTION_MOVE -> if (dragTracking) {
                velocity?.addMovement(ev)
                val dx = ev.rawX - dragStartX
                val dy = ev.rawY - dragStartY
                if (!dragging) {
                    val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop * 2
                    if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                        beginDrag(if (dx < 0) 1 else -1)
                        // Stop the child (scroll / click) that was handling this touch.
                        val cancel = MotionEvent.obtain(ev); cancel.action = MotionEvent.ACTION_CANCEL
                        super.dispatchTouchEvent(cancel); cancel.recycle()
                    }
                }
                if (dragging) { updateDrag(dx); return true }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocity?.addMovement(ev)
                if (dragging) {
                    velocity?.computeCurrentVelocity(1000)
                    endDrag(ev.rawX - dragStartX, velocity?.xVelocity ?: 0f)
                    velocity?.recycle(); velocity = null
                    return true
                }
                dragTracking = false
                velocity?.recycle(); velocity = null
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun beginDrag(dir: Int) {
        settlePages()
        dragDir = dir
        dragging = true
        val target = selectedTab + dir
        dragCurrentRefs = saveRefs()
        if (target in tabNames.indices) {
            clearRefs()
            val neighbor = buildPage(target)
            dragNeighborRefs = saveRefs()
            restoreRefs(dragCurrentRefs!!)
            neighbor.translationX = dir * content.width.toFloat()
            content.addView(neighbor)
            dragNeighbor = neighbor
        }
    }

    private fun updateDrag(dx: Float) {
        val w = content.width.toFloat()
        val page = currentPage ?: return
        val raw = if (dragDir > 0) dx.coerceIn(-w, 0f) else dx.coerceIn(0f, w)
        if (dragNeighbor == null) { page.translationX = raw * 0.25f; return } // edge: rubber band
        page.translationX = raw
        dragNeighbor!!.translationX = raw + dragDir * w
    }

    private fun endDrag(dx: Float, vx: Float) {
        val w = content.width.toFloat()
        val page = currentPage
        val neighbor = dragNeighbor
        val offset = if (dragDir > 0) dx.coerceIn(-w, 0f) else dx.coerceIn(0f, w)
        val flung = Math.abs(vx) > dp(600) && (vx < 0) == (dragDir > 0)
        val commit = neighbor != null && (Math.abs(offset) > w / 3f || flung)
        val target = selectedTab + dragDir
        val savedCurrent = dragCurrentRefs
        val savedNeighbor = dragNeighborRefs
        dragging = false; dragTracking = false
        dragNeighbor = null; dragNeighborRefs = null; dragCurrentRefs = null
        if (page == null) return
        if (commit && neighbor != null) {
            // Travel only the distance that is left, so the speed follows the finger's progress.
            val remaining = 1f - Math.abs(offset) / w
            val duration = (220f * remaining).toLong().coerceAtLeast(60L)
            page.animate().translationX(-dragDir * w).setDuration(duration).setInterpolator(slideInterpolator)
                .withEndAction { content.removeView(page) }.start()
            neighbor.animate().translationX(0f).setDuration(duration).setInterpolator(slideInterpolator).start()
            currentPage = neighbor
            selectedTab = target
            savedNeighbor?.let { restoreRefs(it) }
            updateTabHighlight()
            if (target == TAB_SCREEN && screenAuto) handler.post { scheduleScreenCapture() }
        } else {
            val duration = (220f * Math.abs(offset) / w).toLong().coerceAtLeast(60L)
            page.animate().translationX(0f).setDuration(duration).setInterpolator(slideInterpolator).start()
            neighbor?.animate()?.translationX(dragDir * w)?.setDuration(duration)?.setInterpolator(slideInterpolator)
                ?.withEndAction { content.removeView(neighbor) }?.start()
            savedCurrent?.let { restoreRefs(it) }
        }
    }

    private fun updateTabHighlight() {
        if (!::tabsRow.isInitialized) return
        for (i in 0 until tabsRow.childCount) {
            val view = tabsRow.getChildAt(i) as ImageView
            val active = i == selectedTab
            view.background = if (active) ui.pillBg(R.color.md_secondary_container, 200) else null
            view.imageTintList = android.content.res.ColorStateList.valueOf(color(if (active) R.color.md_on_secondary_container else R.color.md_on_surface_variant))
        }
        centerSelectedTab()
    }

    /** Scrolls the pill nav bar so the selected tab sits in the middle, not just into view. */
    private fun centerSelectedTab() {
        if (!::navScroll.isInitialized || selectedTab >= tabsRow.childCount) return
        val item = tabsRow.getChildAt(selectedTab)
        navScroll.post {
            val target = item.left + item.width / 2 - navScroll.width / 2
            navScroll.smoothScrollTo(target.coerceAtLeast(0), 0)
        }
    }

    // ─────────────────────────────────── Material tokens / helpers ───────────────────────────────────
    private fun color(@androidx.annotation.ColorRes id: Int): Int = androidx.core.content.ContextCompat.getColor(this, id)

    // ─────────────────────────────── Connect dialog: scan + manual + pairing ───────────────────────────────
    private fun openConnectDialog() {
        var dialog: AlertDialog? = null
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }

        if (adb != null) {
            container.addView(ui.label("Connected to $remoteIp:$remotePort", 14f, R.color.md_on_surface, bold = true), matchWrap())
            container.addView(ui.pill("Disconnect") {
                autoReconnect = false
                handler.removeCallbacks(rescan)
                val old = adb
                old?.onClosed = null
                adb = null
                thread { runCatching { old?.close() }; runOnUiThread { updateConnectionChip(); setStatus("Disconnected") } }
            }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8); bottomMargin = dp(8) })
            divider(container)
        }

        container.addView(ui.pill("Connect via USB") {
            dialog?.dismiss()
            connectUsb()
        }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4); bottomMargin = dp(8) })
        divider(container)
        container.addView(ui.label("Wireless debugging", 14f, R.color.md_on_surface, bold = true), matchWrap())

        val scanStatus = ui.label("Scanning for port 5555...", 13f, R.color.md_on_surface_variant)
        container.addView(scanStatus, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        val suggestions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(suggestions, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6); bottomMargin = dp(6) })

        // IP only - no port field. Normal Wireless Debugging devices listen on 5555, so that's
        // always what's used to connect; no need to ask for it.
        val wirelessField = edit(container, "Device IP (or IP:port for Wireless debugging)", if (remoteIp.isNotBlank()) remoteIp else "", InputType.TYPE_CLASS_TEXT)

        val cancelled = AtomicBoolean(false)
        dialog = AlertDialog.Builder(this)
            .setTitle("Connect to device")
            .setView(ScrollView(this).apply { addView(container) })
            .setNegativeButton("Close", null)
            .setOnDismissListener { cancelled.set(true) }
            .create()

        container.addView(ui.pill("Connect", primary = true) {
            val (host, port) = parseHostPort(wirelessField.text.toString())
            if (host.isBlank()) { toast("Enter the device's IP address", error = true); return@pill }
            remoteIp = host; remotePort = port
            connectNetwork(host, port)
            dialog?.dismiss()
        }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4); bottomMargin = dp(8) })

        divider(container)
        container.addView(ui.label("Pair a new device (Wireless debugging)", 14f, R.color.md_on_surface, bold = true), matchWrap())
        val pairIp = edit(container, "Pairing IP:port (shown on the TV)", "", InputType.TYPE_CLASS_TEXT)
        val pairCode = edit(container, "6-digit pairing code", "", InputType.TYPE_CLASS_NUMBER)
        container.addView(ui.label(
            "Wireless debugging > Pair device with pairing code. Enter the IP:port and code shown there, then connect above.",
            12.5f, R.color.md_on_surface_variant
        ).apply { setPadding(0, dp(2), 0, dp(6)) }, matchWrap())
        container.addView(ui.pill("Pair") {
            val (pHost, pPort) = parseHostPort(pairIp.text.toString(), defaultPort = -1)
            val code = pairCode.text.toString().trim()
            if (pHost.isBlank() || pPort <= 0 || code.length != 6) { toast("Enter the pairing IP:port and the 6-digit code", error = true); return@pill }
            pairDevice(pHost, pPort, code) { dialog?.dismiss() }
        }, matchWrap())

        dialog?.show()

        // Any suggested IP found listening on 5555 connects to that device immediately on tap.
        scanForAdbDevices(
            onFound = { ip ->
                if (cancelled.get()) return@scanForAdbDevices
                suggestions.addView(ui.pill(ip) {
                    wirelessField.setText(ip)
                    remoteIp = ip; remotePort = 5555
                    connectNetwork(ip, 5555)
                    dialog?.dismiss()
                }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) })
            },
            onDone = { found ->
                if (cancelled.get()) return@scanForAdbDevices
                scanStatus.text = if (found == 0) "No devices found listening on port 5555." else "$found device(s) found on port 5555."
            }
        )
    }

    /** Pings every host on the device's own /24 subnet on port 5555, in parallel, with a short timeout. */
    private fun scanForAdbDevices(onFound: (String) -> Unit, onDone: (Int) -> Unit) {
        thread(name = "adb-lan-scan") {
            val prefix = localSubnetPrefix()
            if (prefix == null) { runOnUiThread { onDone(0) }; return@thread }
            val pool = Executors.newFixedThreadPool(32)
            val latch = CountDownLatch(254)
            val found = java.util.concurrent.atomic.AtomicInteger()
            for (i in 1..254) {
                pool.execute {
                    try {
                        Socket().use { s -> s.connect(InetSocketAddress("$prefix$i", 5555), 250) }
                        found.incrementAndGet()
                        runOnUiThread { onFound("$prefix$i") }
                    } catch (_: Exception) {
                        // host down or port closed - expected for most of the subnet
                    } finally { latch.countDown() }
                }
            }
            latch.await(6, TimeUnit.SECONDS)
            pool.shutdownNow()
            runOnUiThread { onDone(found.get()) }
        }
    }

    private fun localSubnetPrefix(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress?.substringBeforeLast('.')?.plus(".")
    } catch (e: Exception) { null }

    // ─────────────────────────────── Remote control ───────────────────────────────
    private fun buildRemoteTab(root: LinearLayout) {
        title(root, "Remote")
        paragraph(root, "Connect first, then use the pad.")
        val pad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        pad.addView(button("Up") { sendKey(KeyEvent.UP) }, centeredParams(74))
        val middle = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        middle.addView(button("Left") { sendKey(KeyEvent.LEFT) }, centeredParams(74))
        middle.addView(button("OK") { sendKey(KeyEvent.CENTER) }, centeredParams(74))
        middle.addView(button("Right") { sendKey(KeyEvent.RIGHT) }, centeredParams(74))
        pad.addView(middle)
        pad.addView(button("Down") { sendKey(KeyEvent.DOWN) }, centeredParams(74))
        root.addView(pad, matchWrap())
        row(root, listOf("Back" to { sendKey(KeyEvent.BACK) }, "Home" to { sendKey(KeyEvent.HOME) }, "Vol-" to { sendKey(KeyEvent.VOLUME_DOWN) }, "Vol+" to { sendKey(KeyEvent.VOLUME_UP) }))
        val text = edit(root, "Type text to send to the TV", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        button(root, "Send text") {
            val value = text.text.toString()
            if (value.isNotBlank()) {
                val escaped = value.replace("%", "%%").replace(" ", "%s")
                runShell("input text ${shellQuote(escaped)}")
            }
        }
    }

    /** "192.168.1.5" -> (host, 5555); "192.168.1.5:37123" -> (host, 37123). */
    private fun parseHostPort(text: String, defaultPort: Int = 5555): Pair<String, Int> {
        val t = text.trim()
        val idx = t.lastIndexOf(':')
        if (idx > 0) {
            val port = t.substring(idx + 1).toIntOrNull()
            if (port != null && port in 1..65535) return t.substring(0, idx).trim() to port
        }
        return t to defaultPort
    }

    private fun pairDevice(host: String, port: Int, code: String, onDone: () -> Unit) {
        toast("Pairing with $host:$port...", durationMs = 30_000L)
        thread(name = "adb-pairing") {
            var message = ""
            var error = true
            try {
                com.min.adb.adb.AdbPairingClient(host, port, code, crypto).use { it.pair() }
                message = "Paired with $host. Now connect to it."
                error = false
            } catch (e: com.min.adb.adb.AdbWrongPairingCodeException) {
                message = "Pairing failed: wrong pairing code"
            } catch (e: Throwable) {
                message = "Pairing failed: ${e.message ?: e.javaClass.simpleName}"
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                toast(message, error = error, durationMs = 4000L)
                if (!error) { remoteIp = host; onDone() }
            }
        }
    }

    /** Makes [connection] the active one and starts watching for it dropping. */
    private fun attachConnection(connection: AdbConnection) {
        adb?.let { it.onClosed = null; it.close() }
        adb = connection
        autoReconnect = true
        connection.onClosed = {
            runOnUiThread {
                if (adb === connection) {
                    adb = null
                    updateConnectionChip(); setStatus("Disconnected")
                    scanForLastDevice()
                }
            }
        }
    }

    private fun connectNetwork(host: String, port: Int) {
        if (host.isBlank()) { toast("Enter the TV's IP address"); return }
        setStatus("Connecting to $host:$port...")
        thread(name = "adb-connect-tcp") {
            try {
                val connection = AdbConnection(TcpTransport(host, port), crypto)
                connection.connect()
                attachConnection(connection)
                resetIconPrefetchState()
                if (port == 5555) prefs.edit().putString(PREF_LAST_HOST, host).apply()
                runOnUiThread {
                    updateConnectionChip(); setStatus("Connected - $host:$port; fetching app icons...")
                    // Load packages (and icons) right away instead of waiting for a manual Refresh.
                    if (selectedTab == TAB_PACKAGES) renderTab(TAB_PACKAGES)
                }
                prefetchIconsAfterConnect()
            } catch (e: Exception) {
                runOnUiThread {
                    setStatus("Connection failed: ${e.message ?: e.javaClass.simpleName}")
                    // Auto-reconnect target not accepting yet (booting / waiting for Allow): keep trying.
                    if (autoReconnect && adb == null && host == prefs.getString(PREF_LAST_HOST, null)) handler.postDelayed(rescan, 4000)
                }
            }
        }
    }

    private fun connectUsb() {
        val manager = getSystemService(USB_SERVICE) as UsbManager
        val device: UsbDevice? = manager.deviceList.values.firstOrNull { UsbTransport.findAdbInterface(it) != null }
        if (device == null) { toast("No USB device with an ADB interface found. Check the OTG cable and USB debugging."); return }
        if (!manager.hasPermission(device)) {
            val pi = PendingIntent.getBroadcast(this, 0, Intent("com.min.adb.USB_PERMISSION"), PendingIntent.FLAG_MUTABLE)
            manager.requestPermission(device, pi)
            toast("Grant the USB permission, then tap Connect via USB again")
            return
        }
        setStatus("Connecting over USB...")
        thread(name = "adb-connect-usb") {
            try {
                val connection = AdbConnection(UsbTransport(manager, device), crypto)
                connection.connect(); attachConnection(connection)
                resetIconPrefetchState()
                runOnUiThread {
                    updateConnectionChip(); setStatus("Connected over USB; fetching app icons...")
                    if (selectedTab == TAB_PACKAGES) renderTab(TAB_PACKAGES)
                }
                prefetchIconsAfterConnect()
            } catch (e: Exception) { runOnUiThread { setStatus("USB connection failed: ${e.message}") } }
        }
    }

    private fun sendKey(code: Int) = runShell("input keyevent $code")

    // ─────────────────────────────────── Commands ───────────────────────────────────
    private data class Builtin(val name: String, val command: String?, val action: String? = null)

    private fun buildCommandsTab(root: LinearLayout) {
        title(root, "Commands")
        val packageInput = edit(root, "Package (for Force-stop)", "", InputType.TYPE_CLASS_TEXT)
        val commands = listOf(
            Builtin("Pull down status bar", "cmd statusbar expand-notifications"),
            Builtin("Pull down quick settings", "cmd statusbar expand-settings"),
            Builtin("Collapse status bar", "cmd statusbar collapse"),
            Builtin("Open Screen tab", null, action = "screen"),
            Builtin("Reboot", "reboot"),
            Builtin("Reboot to bootloader", "reboot bootloader"),
            Builtin("Power off", "reboot -p"),
            Builtin("Open Developer options", "am start -a com.android.settings.APPLICATION_DEVELOPMENT_SETTINGS"),
            Builtin("Disable Developer options", "settings put global development_settings_enabled 0"),
            Builtin("Force landscape", "settings put system accelerometer_rotation 0; settings put system user_rotation 1"),
            Builtin("Force portrait", "settings put system accelerometer_rotation 0; settings put system user_rotation 0"),
            Builtin("Auto-rotate on", "settings put system accelerometer_rotation 1"),
            Builtin("Auto-rotate off", "settings put system accelerometer_rotation 0"),
            Builtin("Screen orientation", "settings get system user_rotation"),
            Builtin("Force-stop package", "am force-stop {pkg}"),
            Builtin("Kill background processes", "am kill-all"),
            Builtin("Device info", "getprop ro.serialno; getprop ro.product.model; getprop ro.build.version.release"),
            Builtin("Location status", "dumpsys location"),
            Builtin("Active windows", "dumpsys window windows"),
            Builtin("WLAN IP address", "ip -f inet addr show wlan0 | grep 'inet '"),
            Builtin("List /system/bin", "ls /system/bin"),
            Builtin("Settings (global)", "settings list global"),
            Builtin("Settings (secure)", "settings list secure"),
            Builtin("Settings (system)", "settings list system"),
            Builtin("List permissions", "pm list permissions")
        )
        val rows = commands.map { item ->
            val handler: () -> Unit = handler@{
                if (item.action == "screen") { renderTab(TAB_SCREEN); return@handler }
                var command = item.command ?: return@handler
                if ("{pkg}" in command) {
                    val pkg = packageInput.text.toString().trim()
                    if (pkg.isBlank() || !pkg.matches(Regex("[A-Za-z0-9_.]+"))) { toast("Enter a valid package name first", error = true); return@handler }
                    command = command.replace("{pkg}", shellQuote(pkg))
                }
                val cmd = command
                // Runs immediately. Commands that print nothing just toast "Done"; failures only toast.
                runCommand(cmd) { result -> if (result == "(no output)") toast("Done") else showOutputDialog(item.name, result) }
            }
            ui.Row(item.name, null, onClick = handler)
        }
        root.addView(ui.group(rows), matchWrap())
        divider(root)
        title(root, "Custom command")
        val custom = edit(root, "Enter a shell command", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        row(root, listOf(
            "Run" to { val cmd = custom.text.toString().trim(); if (cmd.isNotEmpty()) runCommand(cmd) { result -> if (result == "(no output)") toast("Done") else showOutputDialog(cmd, result) } },
            "Device info" to { runCommand("getprop; echo '=== BATTERY ==='; dumpsys battery; echo '=== RAM ==='; cat /proc/meminfo; echo '=== STORAGE ==='; df -h /data") { result -> showOutputDialog("Device info", result) } },
            "Logcat" to { runCommand("logcat -d -t 500") { result -> showOutputDialog("Logcat", result) } }
        ))
    }

    /** Commands tab no longer keeps a persistent output panel; every run shows its result here instead. */
    private fun showOutputDialog(cmd: String, result: String) {
        val textView = ui.label(result.ifBlank { "(no output)" }, 13f, R.color.md_on_surface_variant, mono = true).apply {
            setTextIsSelectable(true)
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        AlertDialog.Builder(this)
            .setTitle(cmd.take(80))
            .setView(ScrollView(this).apply { addView(textView) })
            .setPositiveButton("Close", null)
            .show()
    }

    // ─────────────────────────────────── Packages ───────────────────────────────────
    private fun buildPackagesTab(root: LinearLayout) {
        title(root, "Packages")
        val stateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(Triple("All", R.drawable.ic_packages, 0), Triple("Enabled", R.drawable.ic_enabled, 1), Triple("Disabled", R.drawable.ic_disabled, 2)).forEach { (label, icon, value) ->
            stateRow.addView(
                ui.iconPill(icon, label, primary = packageStateFilter == value) { packageStateFilter = value; renderTab(TAB_PACKAGES) }.withPressAnimation(),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
            )
        }
        root.addView(stateRow, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        root.addView(ui.actionRow(
            ui.iconPill(R.drawable.ic_system, if (packageShowSystem) "Hide system apps" else "Show system apps", primary = packageShowSystem) { packageShowSystem = !packageShowSystem; renderTab(TAB_PACKAGES) }.withPressAnimation(),
            ui.iconPill(R.drawable.ic_refresh, "Refresh") { refreshPackages(root) }.withPressAnimation()
        ).apply { setPadding(0, dp(4), 0, 0) }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        val pf = edit(root, "Filter by package name", "", InputType.TYPE_CLASS_TEXT)
        packageFilter = pf
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list, matchWrap())
        val result = outputView(root, "Waiting for the package list...")
        pf.addTextChangedListener(SimpleTextWatcher { filter ->
            list.removeAllViews()
            renderPackageRows(list, filter, result)
        })
        refreshPackages(root) { list.removeAllViews(); renderPackageRows(list, pf.text?.toString().orEmpty(), result) }
    }

    /** Fetches user (-3) and, if requested, system (-s) packages as two separate lists so
     *  renderPackageRows can always show user apps before system apps. */
    private fun refreshPackages(root: LinearLayout, done: (() -> Unit)? = null) {
        val stateFlag = when (packageStateFilter) { 1 -> " -e"; 2 -> " -d"; else -> "" }
        runShell("pm list packages -3$stateFlag") { userRaw ->
            packageRawUser = userRaw
            if (packageShowSystem) {
                runShell("pm list packages -s$stateFlag") { systemRaw ->
                    packageRawSystem = systemRaw
                    if (done != null) done() else renderTab(TAB_PACKAGES)
                }
            } else {
                packageRawSystem = ""
                if (done != null) done() else renderTab(TAB_PACKAGES)
            }
        }
    }

    private fun parsePackageList(raw: String, filter: String): List<String> =
        raw.lineSequence().map { it.trim() }.filter { it.startsWith("package:") }.map { it.removePrefix("package:") }
            .filter { it.isNotBlank() && it.contains(filter, ignoreCase = true) }.distinct().sorted().toList()

    /** User apps first, system apps after - each group sorted alphabetically within itself. */
    private fun renderPackageRows(list: LinearLayout, filter: String, result: TextView) {
        val userPkgs = parsePackageList(packageRawUser, filter)
        val systemPkgs = if (packageShowSystem) parsePackageList(packageRawSystem, filter) else emptyList()
        val packages = userPkgs + systemPkgs
        result.text = "${packages.size} packages shown."
        val views = HashMap<String, Pair<TextView, ImageView>>()
        val cards = packages.map { pkg -> buildPackageCard(pkg, list, views) }
        list.addView(chainedGroup(cards), matchWrap())

        // Fast path: one on-device helper app dumps every icon+label into small PNGs, we tar
        // and pull that single file. Anything it couldn't resolve (or if it fails entirely)
        // falls back to the slower per-app "pull the APK" path so icons still show either way.
        bulkFetchIcons { bulk ->
            for ((pkg, viewPair) in views) {
                val (nameView, icon) = viewPair
                val hit = bulk[pkg]
                if (hit != null) {
                    if (!hit.first.isNullOrBlank()) nameView.text = hit.first
                    if (hit.second != null) icon.setImageBitmap(hit.second)
                } else {
                    loadPackageInfo(pkg, nameView, icon)
                }
            }
        }
    }

    /** One package "card": app name + package name, then small action buttons underneath -
     *  matches the pm/APK flow described in the notes you sent (pm path, pull the APK,
     *  getPackageArchiveInfo for the real icon/label, pm enable/disable-user, am force-stop). */
    private fun buildPackageCard(pkg: String, listOwner: LinearLayout, views: MutableMap<String, Pair<TextView, ImageView>>): LinearLayout {
        val icon = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_INSIDE }
        val nameView = ui.label(pkg, 16.5f, R.color.md_on_surface, bold = true).apply { setPadding(0, 0, 0, dp(1)) }
        val pkgView = ui.label(pkg, 13f, R.color.md_on_surface_variant)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
            addView(
                LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; addView(nameView); addView(pkgView) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(header, matchWrap())
        }
        val actions = ui.actionRow(
            ui.iconPill(R.drawable.ic_launch, "Launch") { runCommand("monkey -p ${shellQuote(pkg)} -c android.intent.category.LAUNCHER 1") { toast(shortResult(it, "Launched $pkg")) } },
            ui.iconPill(R.drawable.ic_stop, "Force stop") { runCommand("am force-stop ${shellQuote(pkg)}") { toast(shortResult(it, "Stopped $pkg")) } },
            ui.iconPill(R.drawable.ic_power, "Enable") { runCommand("pm enable ${shellQuote(pkg)}") { toast(shortResult(it, "Enabled $pkg")) } },
            ui.iconPill(R.drawable.ic_disabled, "Disable") {
                val disable = { runCommand("pm disable-user --user 0 ${shellQuote(pkg)}") { toast(shortResult(it, "Disabled $pkg")) } }
                // Only system packages get the warning; user apps are disabled straight away.
                if (pkg in parsePackageList(packageRawSystem, "")) {
                    AlertDialog.Builder(this).setTitle("Disable $pkg?").setMessage("Disabling a system package can break features until re-enabled.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Disable") { _, _ -> disable() }.show()
                } else disable()
            },
            ui.iconPill(R.drawable.ic_delete, "Uninstall") {
                AlertDialog.Builder(this).setTitle("Uninstall $pkg?").setMessage("Uninstall $pkg for the current user on the target device?")
                    .setNegativeButton("Cancel", null).setPositiveButton("Uninstall") { _, _ ->
                        runCommand("pm uninstall --user 0 ${shellQuote(pkg)}") { toast(shortResult(it, "Uninstalled $pkg")); refreshPackages(listOwner) }
                    }.show()
            }
        )
        // Tools stay collapsed until the card header (icon + name) is tapped.
        actions.visibility = View.GONE
        header.setOnClickListener { actions.visibility = if (actions.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        card.addView(actions, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        views[pkg] = nameView to icon
        return card
    }

    // ──────────────────────── Fast bulk icon/label fetch via the on-device helper ────────────────────────
    private val HELPER_PKG = "com.min.adb.iconhelper"
    private val HELPER_ACTION = "com.min.adb.iconhelper.DUMP_ICONS"
    private val HELPER_REMOTE_DIR = "/sdcard/adb_icons"
    private val bulkIconCache = HashMap<String, Pair<String?, Bitmap?>>()
    @Volatile private var iconsPrefetched = false
    @Volatile private var bulkIconError: String? = null
    // Connection-time prefetch and the Packages screen can request icons at almost the same
    // time. Keep one pull in flight and deliver its result to every caller.
    private var iconFetchInProgress = false
    private val iconFetchCallbacks = mutableListOf<(Map<String, Pair<String?, Bitmap?>>) -> Unit>()

    private fun resetIconPrefetchState() {
        synchronized(bulkIconCache) {
            bulkIconCache.clear()
            iconsPrefetched = false
            bulkIconError = null
        }
    }

    /** Installs (once) a tiny headless helper app on the TV that reads every icon/label straight
     *  from its own on-device PackageManager - no per-app APK pulls needed. It writes small PNGs
     *  plus a manifest.csv, we tar them into ONE file on-device and pull just that, which is far
     *  faster than pulling one full APK per installed app. Falls back silently (empty map) to let
     *  the caller use the old per-app pull for whatever this couldn't resolve. */
    private fun bulkFetchIcons(onResult: (Map<String, Pair<String?, Bitmap?>>) -> Unit) {
        synchronized(bulkIconCache) {
            if (iconsPrefetched) {
                val cached = HashMap(bulkIconCache)
                runOnUiThread { onResult(cached) }
                return
            }
            iconFetchCallbacks.add(onResult)
            if (iconFetchInProgress) return
            iconFetchInProgress = true
        }

        val connection = adb
        if (connection == null) {
            finishIconFetch(emptyMap(), "Not connected to ADB")
            return
        }
        thread(name = "adb-bulk-icons") {
            val result = HashMap<String, Pair<String?, Bitmap?>>()
            var error: String? = null
            try {
                // 1) Ensure the helper is installed. The APK is bundled by copyIconHelperApk
                // during the normal Gradle preBuild task.
                val pathCheck = connection.shell("pm path $HELPER_PKG")
                val installedVersionLine = if (pathCheck.contains("package:")) {
                    connection.shell("dumpsys package $HELPER_PKG | grep versionCode")
                } else ""
                val installedVersion = Regex("versionCode=(\\d+)").find(installedVersionLine)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                // Version 2 adds QUERY_ALL_PACKAGES. Replace an older helper so an installation
                // from a previous build cannot silently keep returning a filtered package list.
                if (installedVersion < 2) {
                    if (installedVersion > 0) connection.shell("pm uninstall $HELPER_PKG")
                    val apk = File(cacheDir, "iconhelper.apk")
                    assets.open("iconhelper.apk").use { input -> apk.outputStream().use { input.copyTo(it) } }
                    FileInputStream(apk).use { input -> AdbSyncClient(connection).push(input, "/data/local/tmp/iconhelper.apk") }
                    val install = connection.shell("pm install -r -g /data/local/tmp/iconhelper.apk")
                    apk.delete()
                    if (!install.contains("Success", ignoreCase = true)) throw IOException("helper install failed: $install")
                }

                // 2) Clear stale files, explicitly target the receiver, and wait for DONE.
                connection.shell("rm -rf $HELPER_REMOTE_DIR")
                val broadcast = connection.shell("am broadcast -n $HELPER_PKG/.DumpReceiver -a $HELPER_ACTION")
                if (broadcast.contains("Error", ignoreCase = true) || broadcast.contains("not found", ignoreCase = true)) {
                    throw IOException("could not start icon helper: $broadcast")
                }
                var waited = 0
                var completed = false
                while (waited < 30_000) {
                    val done = connection.shell("test -f $HELPER_REMOTE_DIR/DONE && echo DONE")
                    if (done.contains("DONE")) { completed = true; break }
                    Thread.sleep(400)
                    waited += 400
                }
                if (!completed) throw IOException("icon helper timed out (no DONE file)")

                // 3) Create one archive and pull it to the phone in a single ADB sync transfer.
                val remoteTar = "$HELPER_REMOTE_DIR.tar"
                val tarResult = connection.shell("tar -cf $remoteTar -C $HELPER_REMOTE_DIR .")
                val tarCheck = connection.shell("test -s $remoteTar && echo TAR_OK")
                if (!tarCheck.contains("TAR_OK")) throw IOException("could not create icon archive: $tarResult")
                val localTar = File(cacheDir, "adb_icons.tar")
                FileOutputStream(localTar).use { out -> AdbSyncClient(connection).pull(remoteTar, out) }
                connection.shell("rm -rf $HELPER_REMOTE_DIR $remoteTar")

                // 4) Decode the pulled PNGs and their labels into the in-memory icon cache.
                val entries = untar(localTar)
                localTar.delete()
                val manifest = entries["manifest.csv"]?.toString(Charsets.UTF_8).orEmpty()
                val labels = manifest.lineSequence().mapNotNull { line ->
                    val idx = line.indexOf(',')
                    if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
                }.toMap()
                for ((name, bytes) in entries) {
                    if (!name.endsWith(".png")) continue
                    val pkg = name.removeSuffix(".png")
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                    result[pkg] = labels[pkg] to bmp
                }
                if (result.isEmpty()) throw IOException("helper completed but returned no icons")
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            finishIconFetch(result, error)
        }
    }

    private fun finishIconFetch(result: Map<String, Pair<String?, Bitmap?>>, error: String?) {
        val callbacks: List<(Map<String, Pair<String?, Bitmap?>>) -> Unit>
        synchronized(bulkIconCache) {
            bulkIconCache.clear()
            bulkIconCache.putAll(result)
            iconsPrefetched = result.isNotEmpty()
            bulkIconError = error
            iconFetchInProgress = false
            callbacks = iconFetchCallbacks.toList()
            iconFetchCallbacks.clear()
        }
        runOnUiThread { callbacks.forEach { callback -> runCatching { callback(result) } } }
    }

    /** Fetch icons immediately after ADB connects, so the package icons are ready before
     *  the user opens the Packages tab. Uses the existing bulk pull path only. */
    private fun prefetchIconsAfterConnect() {
        synchronized(bulkIconCache) {
            if (iconsPrefetched) return
        }
        bulkFetchIcons { result ->
            if (result.isNotEmpty()) {
                setStatus("ADB connected — ${result.size} app icons fetched")
            } else {
                val detail = bulkIconError?.let { ": $it" }.orEmpty()
                setStatus("ADB connected, but icon prefetch failed$detail. Open Packages to retry.")
            }
        }
    }

    /** Minimal reader for the flat POSIX ustar files Android's toybox `tar` produces here - just
     *  enough to read back the file set written by the helper app (no subdirectories). */
    private fun untar(file: File): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(512)
            while (raf.read(header) == 512) {
                if (header.all { it == 0.toByte() }) break
                val name = String(header, 0, 100, Charsets.US_ASCII).trim('\u0000', ' ')
                val sizeOctal = String(header, 124, 12, Charsets.US_ASCII).trim('\u0000', ' ')
                val size = if (sizeOctal.isBlank()) 0L else sizeOctal.toLong(8)
                if (name.isNotBlank() && !name.endsWith("/")) {
                    val data = ByteArray(size.toInt())
                    raf.readFully(data)
                    out[name.substringAfterLast('/')] = data
                } else {
                    raf.skipBytes(size.toInt())
                }
                val padding = ((512 - (size % 512)) % 512).toInt()
                raf.skipBytes(padding)
            }
        }
        return out
    }

    /** pm path -> pull the APK into cache -> getPackageArchiveInfo for the real icon + label.
     *  Runs automatically for every row so icons/labels show by default; failures are silent
     *  and just leave that row on its package-name fallback. */
    private fun loadPackageInfo(pkg: String, nameView: TextView, icon: ImageView) {
        thread(name = "adb-pull-apk") {
            var cacheFile: File? = null
            try {
                val connection = requireAdb()
                val pathLine = connection.shell("pm path ${shellQuote(pkg)}")
                    .lineSequence().firstOrNull { it.trim().startsWith("package:") }
                    ?: throw IOException("pm path returned nothing")
                val remotePath = pathLine.trim().removePrefix("package:")
                val dir = File(cacheDir, "pkg_icons").apply { mkdirs() }
                val file = File(dir, "$pkg.apk")
                cacheFile = file
                FileOutputStream(file).use { out -> AdbSyncClient(connection).pull(remotePath, out) }

                val pm = packageManager
                val archiveInfo = pm.getPackageArchiveInfo(file.absolutePath, 0)
                val appInfo = archiveInfo?.applicationInfo
                val label = appInfo?.let {
                    it.sourceDir = file.absolutePath
                    it.publicSourceDir = file.absolutePath
                    it.loadLabel(pm).toString()
                }
                val drawable = appInfo?.loadIcon(pm)

                runOnUiThread {
                    if (!label.isNullOrBlank()) nameView.text = label
                    if (drawable != null) icon.setImageDrawable(drawable)
                }
            } catch (e: Exception) {
                // Silent: row just keeps showing the package name with no icon.
            } finally {
                cacheFile?.let { runCatching { it.delete() } }
            }
        }
    }

    /** Vertically chained cards: rounded corners only at the very top and bottom of the
     *  group, square in between, with a 2dp gap as the "divider" - same shape family as
     *  .list-container/.tweak-row in style.css, used for lists that aren't plain Ui.Row. */
    private fun chainedGroup(cards: List<LinearLayout>): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        cards.forEachIndexed { i, card ->
            val pos = when {
                cards.size == 1 -> Ui.Pos.SINGLE
                i == 0 -> Ui.Pos.TOP
                i == cards.size - 1 -> Ui.Pos.BOTTOM
                else -> Ui.Pos.MIDDLE
            }
            card.background = ui.rowBg(pos)
            addView(card, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) topMargin = dp(2) })
        }
    }

    // ─────────────────────────────────── Processes ───────────────────────────────────
    private fun buildProcessesTab(root: LinearLayout) {
        title(root, "Processes")
        paragraph(root, "From ps -A. Columns vary by Android version.")
        row(root, listOf("Refresh" to { refreshProcesses() }, "RAM info" to { runShell("cat /proc/meminfo") { processRaw = it; renderProcessOutput() } }))
        processFilter = edit(root, "Filter by PID / user / process name", "", InputType.TYPE_CLASS_TEXT)
        val out = outputView(root, "Tap Refresh to fetch the process list")
        out.tag = "process-output"
        processFilter?.addTextChangedListener(SimpleTextWatcher { renderProcessOutput(out) })
        refreshProcesses { renderProcessOutput(out) }
    }

    private fun refreshProcesses(done: (() -> Unit)? = null) {
        runShell("ps -A -o USER,PID,PPID,RSS,VSZ,STAT,NAME") { raw -> processRaw = raw; done?.invoke() ?: renderTab(TAB_PROCESSES) }
    }

    private fun renderProcessOutput(target: TextView? = null) {
        val filterText = processFilter?.text?.toString() ?: ""
        val filtered = processRaw.lineSequence().filter { line -> line.contains(filterText, ignoreCase = true) }.joinToString("\n")
        (target ?: findOutputInContent())?.text = filtered.ifBlank { "No data yet. Refresh after connecting over ADB." }
    }

    private fun findOutputInContent(): TextView? {
        fun search(v: View): TextView? {
            if (v is TextView && v.tag == "process-output") return v
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    val found = search(v.getChildAt(i))
                    if (found != null) return found
                }
            }
            return null
        }
        return currentPage?.let { search(it) }
    }

    // ─────────────────────────────────── Screen capture / polling mirror ───────────────────────────────────
    private fun buildScreenTab(root: LinearLayout) {
        title(root, "Screen")
        paragraph(root, "Polls screencap; not a live stream. Needs base64 on the device.")
        val image = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Target device screenshot"
            setBackgroundColor(0xFF202124.toInt())
            minimumHeight = dp(220)
        }
        screenImage = image
        root.addView(image, LinearLayout.LayoutParams(-1, dp(320)))
        row(root, listOf(
            "Capture" to { captureScreen() },
            (if (screenAuto) "Stop auto-refresh" else "Auto-refresh every 2s") to {
                screenAuto = !screenAuto
                renderTab(TAB_SCREEN)
            }
        ))
        button(root, "Save screenshot to phone") { saveCurrentScreenshot() }
    }

    private var lastScreenshot: ByteArray? = null
    private fun captureScreen() {
        val target = screenImage ?: return
        if (!screenBusy.compareAndSet(false, true)) return
        runShell("screencap -p | base64") { raw ->
            try {
                val bytes = android.util.Base64.decode(raw.filterNot { it.isWhitespace() }, android.util.Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap == null) toast("Could not decode the image. The device may be missing the base64 command.")
                else { lastScreenshot = bytes; target.setImageBitmap(bitmap) }
            } catch (e: Exception) { toast("Screen capture failed: ${e.message}") }
            finally { screenBusy.set(false); if (screenAuto && selectedTab == TAB_SCREEN) handler.postDelayed({ scheduleScreenCapture() }, 2000) }
        }
    }

    private fun scheduleScreenCapture() { if (screenAuto && selectedTab == TAB_SCREEN) captureScreen() }

    private fun saveCurrentScreenshot() {
        val bytes = lastScreenshot ?: run { toast("Capture a screenshot first"); return }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "image/png"; putExtra(Intent.EXTRA_TITLE, "tv-screen-${System.currentTimeMillis()}.png")
        }
        pendingScreenshot = bytes
        startActivityForResult(intent, REQUEST_SAVE_SCREEN)
    }
    private var pendingScreenshot: ByteArray? = null

    // ─────────────────────────────────── File manager / ADB SYNC ───────────────────────────────────
    private fun buildFilesTab(root: LinearLayout) {
        title(root, "Files")
        paragraph(root, "Browse and transfer files over ADB SYNC.")
        val path = edit(root, "Path on the device", "/sdcard", InputType.TYPE_CLASS_TEXT)
        filePathEdit = path
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val output = outputView(root, "Tap List to open a directory")
        root.removeView(output) // keep original order: buttons, list, output
        // Page views are captured here (not read from fields later) so a slow reply can't land on another tab.
        lateinit var reloadFn: (String) -> Unit
        reloadFn = { p -> listRemoteFiles(p) { entries -> renderFileEntries(entries, list, output, path, reloadFn) } }
        val reload = reloadFn
        row(root, listOf(
            "List" to { reload(path.text.toString()) },
            "Up to parent" to { path.setText(parentPath(path.text.toString())); reload(path.text.toString()) },
            "New folder" to { askText("New folder name") { name -> runCommand("mkdir -p ${shellQuote(joinRemote(path.text.toString(), name))}") { toast("Folder created"); reload(path.text.toString()) } } }
        ))
        row(root, listOf(
            "Push file to TV" to { pendingPushPath = path.text.toString().trim().ifBlank { "/sdcard" }; pickLocalFile() },
            "Pull path from TV" to { askText("File path on the TV") { remote -> pendingPullPath = remote; createLocalFile(remote) } }
        ))
        root.addView(list, matchWrap())
        root.addView(output, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        fileListContainer = list
        fileOutput = output
        reload(path.text.toString())
    }

    private fun listRemoteFiles(path: String, done: ((List<String>) -> Unit)? = null) {
        val safe = path.trim().ifBlank { "/sdcard" }
        runShell("ls -1Ap ${shellQuote(safe)}") { raw ->
            val entries = raw.lineSequence().map { it.trimEnd('\r') }.filter { it.isNotBlank() && it != "Permission denied" && !it.startsWith("ls: ") }.toList()
            done?.invoke(entries)
        }
    }

    private fun renderFileEntries(entries: List<String>, list: LinearLayout, output: TextView, path: EditText, reload: (String) -> Unit) {
        list.removeAllViews()
        output.text = if (entries.isEmpty()) "Empty directory or no read permission." else "${entries.size} items in ${path.text}"
        val rows = entries.map { entry ->
            val isDir = entry.endsWith("/")
            val name = entry.removeSuffix("/")
            ui.Row(entry, onClick = {
                val full = joinRemote(path.text.toString(), name)
                if (isDir) { path.setText(full); reload(full) }
                else showFileActions(full, output) { reload(path.text.toString()) }
            })
        }
        list.addView(ui.group(rows), matchWrap())
    }

    private fun showFileActions(remotePath: String, output: TextView, refreshed: () -> Unit) {
        val options = arrayOf("Download to phone", "Read text file head", "Delete file")
        AlertDialog.Builder(this).setTitle(remotePath).setItems(options) { _, which ->
            when (which) {
                0 -> { pendingPullPath = remotePath; createLocalFile(remotePath) }
                1 -> runShell("head -c 65536 ${shellQuote(remotePath)}") { output.text = it }
                2 -> AlertDialog.Builder(this).setMessage("Delete $remotePath on the target device?").setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete") { _, _ -> runCommand("rm -f ${shellQuote(remotePath)}") { toast("Deleted"); refreshed() } }.show()
            }
        }.show()
    }

    private fun pickLocalFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
        startActivityForResult(intent, REQUEST_PUSH_FILE)
    }

    private fun createLocalFile(remote: String) {
        val name = remote.substringAfterLast('/').ifBlank { "download.bin" }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/octet-stream"; putExtra(Intent.EXTRA_TITLE, name) }
        startActivityForResult(intent, REQUEST_PULL_FILE)
    }

    private fun transferPush(uri: Uri) {
        val dir = pendingPushPath.orEmpty().trim().ifBlank { "/sdcard" }
        val name = displayName(uri) ?: "upload.bin"
        val target = if (dir.endsWith("/")) dir + name else "$dir/$name"
        setStatus("Sending $name...")
        thread(name = "adb-sync-push") {
            try {
                val input = contentResolver.openInputStream(uri) ?: throw IOException("Could not open the source file")
                input.use { AdbSyncClient(requireAdb()).push(it, target) }
                runOnUiThread { setStatus("Sent: $target") }
            } catch (e: Exception) { runOnUiThread { setStatus("Send failed: ${e.message}") } }
        }
    }

    private fun transferPull(uri: Uri) {
        val remote = pendingPullPath ?: return
        setStatus("Downloading $remote...")
        thread(name = "adb-sync-pull") {
            try {
                val out = contentResolver.openOutputStream(uri) ?: throw IOException("Could not open the destination file")
                out.use { AdbSyncClient(requireAdb()).pull(remote, it) }
                runOnUiThread { setStatus("Downloaded: $remote") }
            } catch (e: Exception) { runOnUiThread { setStatus("Download failed: ${e.message}") } }
        }
    }

    private fun displayName(uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) cursor.getString(0) else uri.lastPathSegment?.substringAfterLast('/')
        } finally { cursor?.close() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data
        when (requestCode) {
            REQUEST_PUSH_FILE -> if (uri != null) transferPush(uri)
            REQUEST_PULL_FILE -> if (uri != null) transferPull(uri)
            REQUEST_SIDELOAD -> {
                val clip = data?.clipData
                val uris = if (clip != null) (0 until clip.itemCount).map { clip.getItemAt(it).uri } else listOfNotNull(uri)
                sideloadUris(uris)
            }
            REQUEST_SAVE_SCREEN -> if (uri != null) {
                val bytes = pendingScreenshot ?: return
                thread { runCatching { contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }
                    .onSuccess { runOnUiThread { setStatus("Screenshot saved") } }
                    .onFailure { runOnUiThread { setStatus("Save failed: ${it.message}") } } }
            }
        }
    }

    // ─────────────────────────────────── Shared UI and ADB helpers ───────────────────────────────────
    private fun runShell(command: String, callback: ((String) -> Unit)? = null) {
        val connection = adb
        if (connection == null) { setStatus("Connect to the ADB device first"); callback?.invoke("Not connected"); return }
        thread(name = "adb-shell") {
            try {
                val result = connection.shell(command).take(8_000_000)
                runOnUiThread { callback?.invoke(result.ifBlank { "(no output)" }) }
            } catch (e: Exception) {
                runOnUiThread { callback?.invoke("Error: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    /**
     * Runs a user-visible command and tracks its exit status (appends `echo $?`). On success
     * [onSuccess] gets the output; on failure NOTHING is shown over the app except the in-app
     * toast at the top, so a failed command can never cover the UI with an output dialog.
     */
    private class CmdResult(val output: String, val ok: Boolean, val reason: String)

    /** Blocking: runs [command] on the device and reports output + exit status. Call off the UI thread. */
    private fun execCommand(connection: AdbConnection, command: String, timeoutMs: Long = 60_000): CmdResult {
        return try {
            val stream = connection.open("shell:$command\necho \"$RC_MARK\$?\"")
            val raw = stream.readAllText(timeoutMs)
            val remoteClosed = stream.closed
            runCatching { stream.close() }
            val idx = raw.lastIndexOf(RC_MARK)
            if (idx >= 0) {
                val rc = raw.substring(idx + RC_MARK.length).trim().toIntOrNull() ?: 0
                CmdResult(raw.substring(0, idx).trimEnd().take(8_000_000), rc == 0, if (rc == 0) "" else "exit $rc")
            } else {
                // No marker: fine if the device closed the stream itself (reboot, power off...),
                // but if it's still open we just ran out of patience.
                CmdResult(raw.take(8_000_000), remoteClosed, if (remoteClosed) "" else "timed out after ${timeoutMs / 1000}s without output")
            }
        } catch (e: Exception) {
            CmdResult("", false, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun runCommand(command: String, onSuccess: (String) -> Unit) {
        val connection = adb
        if (connection == null) { toast("Failed: not connected to an ADB device", error = true); return }
        thread(name = "adb-command") {
            val r = execCommand(connection, command)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (r.ok) onSuccess(r.output.ifBlank { "(no output)" })
                else {
                    val hint = r.output.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(80)
                    toast("Failed (${r.reason})" + (hint?.let { ": $it" } ?: ""), error = true)
                }
            }
        }
    }

    // ─────────────────────────────────── Sideload APK ───────────────────────────────────
    private class ApkSource(val name: String, val open: () -> java.io.InputStream)
    private val sideloading = AtomicBoolean(false)
    private fun isArchive(name: String) = name.lowercase().let { it.endsWith(".apks") || it.endsWith(".apkm") || it.endsWith(".xapk") || it.endsWith(".zip") }

    /** Skips ABI config splits that don't match the target device (e.g. x86 splits on an arm64 TV). */
    private fun abiAllowed(entryName: String, deviceAbis: List<String>): Boolean {
        val m = Regex("config\\.(arm64_v8a|armeabi_v7a|x86_64|x86|armeabi)\\.apk$").find(entryName.lowercase().replace('-', '_')) ?: return true
        return deviceAbis.isEmpty() || m.groupValues[1] in deviceAbis
    }

    private fun openSideloadChooser() {
        if (adb == null) { toast("Failed: connect to a device first", error = true); return }
        AlertDialog.Builder(this).setTitle("Sideload APK")
            .setItems(arrayOf("From phone storage (APK / APKS / APKM / XAPK)", "From installed apps on this phone")) { _, which ->
                if (which == 0) pickApkFiles() else pickInstalledApp()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun pickApkFiles() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true) // select base + split APKs together
        }
        startActivityForResult(intent, REQUEST_SIDELOAD)
    }

    private fun sideloadUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val sources = uris.map { uri ->
            ApkSource(displayName(uri) ?: "app.apk") { contentResolver.openInputStream(uri) ?: throw IOException("Could not open the file") }
        }
        val bad = sources.firstOrNull { !it.name.endsWith(".apk", true) && !isArchive(it.name) }
        if (bad != null) { toast("Unsupported file: ${bad.name} (need .apk, .apks, .apkm or .xapk)", error = true); return }
        sideload(sources, sources.first().name)
    }

    private fun pickInstalledApp() {
        toast("Loading apps...")
        thread(name = "list-installed-apps") {
            val pm = packageManager
            val apps = pm.getInstalledApplications(0)
                .filter { (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 || (it.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 }
                .filter { it.packageName != packageName }
                .map { Triple(it.loadLabel(pm).toString(), it, runCatching { it.loadIcon(pm) }.getOrNull()) }
                .sortedBy { it.first.lowercase() }
            runOnUiThread { if (!isFinishing && !isDestroyed) showInstalledAppsDialog(apps) }
        }
    }

    private fun showInstalledAppsDialog(apps: List<Triple<String, android.content.pm.ApplicationInfo, android.graphics.drawable.Drawable?>>) {
        dismissToast()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var dialog: AlertDialog? = null
        fun fill(filter: String) {
            list.removeAllViews()
            apps.filter { filter.isBlank() || it.first.contains(filter, true) || it.second.packageName.contains(filter, true) }.forEach { (label, info, icon) ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4), dp(8), dp(4), dp(8)); isClickable = true; isFocusable = true
                    background = ui.pillBg(R.color.md_surface_container_highest, 16)
                    addView(ImageView(context).apply { setImageDrawable(icon) }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12); marginStart = dp(8) })
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(ui.label(label, 15f, R.color.md_on_surface, bold = true))
                        addView(ui.label(info.packageName, 12f, R.color.md_on_surface_variant))
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    setOnClickListener {
                        dialog?.dismiss()
                        val files = listOf(info.sourceDir) + (info.splitSourceDirs?.toList() ?: emptyList())
                        val names = files.mapIndexed { i, f -> if (i == 0) "base.apk" else File(f).name }
                        sideload(files.mapIndexed { i, f -> ApkSource(names[i]) { FileInputStream(f) } }, label)
                    }
                }
                list.addView(row, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) })
            }
        }
        val search = EditText(this).apply {
            hint = "Filter apps"; setSingleLine(); textSize = 15f
            setTextColor(color(R.color.md_on_surface)); setHintTextColor(color(R.color.md_on_surface_variant))
            background = ui.pillBg(R.color.md_surface_container_highest, 24); setPadding(dp(18), dp(10), dp(18), dp(10))
            addTextChangedListener(SimpleTextWatcher { fill(it) })
        }
        root.addView(search, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, dp(380)))
        fill("")
        dialog = AlertDialog.Builder(this).setTitle("Install on the connected device").setView(root)
            .setNegativeButton("Cancel", null).show()
    }

    /** Pushes the APK(s) to /data/local/tmp on the target, runs pm install / install-multiple, cleans up. */
    private fun sideload(sources: List<ApkSource>, label: String) {
        val connection = adb
        if (connection == null) { toast("Failed: connect to a device first", error = true); return }
        if (!sideloading.compareAndSet(false, true)) { toast("Another install is already running", error = true); return }
        toast("Installing $label...", durationMs = 10 * 60_000L)
        thread(name = "adb-sideload") {
            val dir = "/data/local/tmp/adb_sideload_${System.currentTimeMillis()}"
            var message = ""
            var error = true
            try {
                val mk = execCommand(connection, "mkdir -p $dir")
                if (!mk.ok) throw IOException("could not create temp dir (${mk.reason})")
                val abis = execCommand(connection, "getprop ro.product.cpu.abilist", 15_000).output
                    .trim().split(',').map { it.trim().replace('-', '_') }.filter { it.isNotBlank() }
                val remotes = mutableListOf<String>()
                var n = 0
                for (src in sources) {
                    if (isArchive(src.name)) {
                        // .apks / .apkm / .xapk are zips of split APKs: push every APK inside (OBB/other files ignored).
                        val names = java.util.zip.ZipInputStream(src.open()).use { z ->
                            generateSequence { z.nextEntry }.map { it.name }.filter { it.endsWith(".apk", true) }.toList()
                        }
                        val useSplits = names.any { it.startsWith("splits/") } // bundletool .apks also has standalones/
                        val wanted = names.filter { (!useSplits || it.startsWith("splits/")) && abiAllowed(it, abis) }.toSet()
                        if (wanted.isEmpty()) throw IOException("no APK found inside ${src.name}")
                        java.util.zip.ZipInputStream(src.open()).use { z ->
                            var e = z.nextEntry
                            while (e != null) {
                                if (e.name in wanted) {
                                    val remote = "$dir/${n++}.apk"
                                    AdbSyncClient(connection).push(z, remote)
                                    remotes += remote
                                }
                                e = z.nextEntry
                            }
                        }
                    } else {
                        val remote = "$dir/${n++}.apk"
                        src.open().use { AdbSyncClient(connection).push(it, remote) }
                        remotes += remote
                    }
                }
                val args = remotes.joinToString(" ") { shellQuote(it) }
                val cmd = if (remotes.size == 1) "pm install -r -t $args" else "pm install-multiple -r -t $args"
                val r = execCommand(connection, cmd, timeoutMs = 180_000)
                if (r.ok && r.output.contains("Success", ignoreCase = true)) { message = "Installed $label"; error = false }
                else {
                    val line = r.output.lineSequence().map { it.trim() }.lastOrNull { it.isNotBlank() }?.take(100)
                    message = "Install failed" + (line?.let { ": $it" } ?: if (r.reason.isNotBlank()) " (${r.reason})" else "")
                }
            } catch (e: Exception) {
                message = "Install failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                runCatching { execCommand(connection, "rm -rf $dir", 20_000) }
                sideloading.set(false)
            }
            runOnUiThread { if (!isFinishing && !isDestroyed) toast(message, error = error) }
        }
    }

    /** One short line for a toast: the command's first line if it printed one, else [fallback]. */
    private fun shortResult(output: String, fallback: String): String {
        val line = output.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() && it != "(no output)" }
        return if (line != null && line.startsWith("Success", true)) line.take(60) else fallback
    }

    private fun requireAdb(): AdbConnection = adb ?: throw IOException("Not connected")
    private fun setStatus(value: String) { statusView.text = value }

    /** In-app toast pinned to the top of this Activity's own view tree (not android.widget.Toast),
     *  so it disappears with the app: removed on pause/stop/destroy instead of lingering. */
    private fun toast(value: String, error: Boolean = false, durationMs: Long = 0L) {
        if (!::rootFrame.isInitialized) return
        dismissToast()
        val view = TextView(this).apply {
            text = value
            textSize = 14f
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setTextColor(color(if (error) R.color.md_on_error_container else R.color.md_on_secondary_container))
            background = ui.pillBg(if (error) R.color.md_error_container else R.color.md_secondary_container, 20)
            elevation = dp(10).toFloat()
            isClickable = true
            setOnClickListener { dismissToast() }
            alpha = 0f
            translationY = -dp(16).toFloat()
        }
        rootFrame.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(16); marginStart = dp(16); marginEnd = dp(16)
        })
        view.animate().alpha(1f).translationY(0f).setDuration(180).start()
        toastView = view
        handler.postDelayed(toastHide, if (durationMs > 0) durationMs else if (error) 3500 else 2500)
    }

    private fun dismissToast() {
        handler.removeCallbacks(toastHide)
        toastView?.let { v -> v.animate().cancel(); rootFrame.removeView(v) }
        toastView = null
    }

    override fun onPause() {
        dismissToast()
        super.onPause()
    }
    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    private fun joinRemote(parent: String, child: String): String = (parent.trimEnd('/') + "/" + child.trimStart('/')).ifBlank { "/" }
    private fun parentPath(path: String): String = path.trimEnd('/').substringBeforeLast('/', "").ifBlank { "/" }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun centeredParams(width: Int) = LinearLayout.LayoutParams(dp(width), ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER }

    // .list-title (section header) / plain body text — delegates to Ui so every screen
    // matches the same style.css tokens and shapes as Ui.kt (group rows, pills, chips).
    private fun title(root: LinearLayout, text: String) {
        root.addView(ui.listTitle(text), matchWrap())
    }
    private fun paragraph(root: LinearLayout, text: String) {
        root.addView(ui.label(text, 14f, R.color.md_on_surface_variant).apply {
            setLineSpacing(dp(2).toFloat(), 1f); setPadding(dp(4), dp(2), dp(4), dp(10))
        }, matchWrap())
    }
    private fun divider(root: LinearLayout) {
        root.addView(View(this).apply { setBackgroundColor(color(R.color.md_outline_variant)) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(12); bottomMargin = dp(12) })
    }

    /** .btn — tonal pill (TextView, not MaterialButton, to match Ui.pill() exactly) with a .btn:active-style press scale. */
    private fun button(text: String, action: () -> Unit): TextView = ui.pill(text, primary = false) { action() }.withPressAnimation()
    private fun button(root: LinearLayout, text: String, action: () -> Unit) { root.addView(button(text, action), matchWrap()) }

    /** CSS has no explicit :active rule for .btn, but every interactive surface in the app
     *  gets a short scale-down "press" to read as tappable — same spirit as the row/switch
     *  transitions in style.css (background/border-radius .15s). */
    private fun <T : View> T.withPressAnimation(): T = apply {
        setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(120).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false // let the normal click/long-click listeners still fire
        }
    }

    /** Rounded "tweak-row"-style input, same shape family as .tweak-row in style.css. */
    private fun edit(root: LinearLayout, hint: String, value: String, inputType: Int): EditText {
        val field = EditText(this).apply {
            this.hint = hint; setText(value); this.inputType = inputType; textSize = 15f
            setSingleLine((inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0)
            setTextColor(color(R.color.md_on_surface))
            setHintTextColor(color(R.color.md_on_surface_variant))
            background = ui.pillBg(R.color.md_surface_container_highest, 24)
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        root.addView(field, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        return field
    }
    private fun row(root: LinearLayout, actions: List<Pair<String, () -> Unit>>) {
        val row = ui.actionRow(*actions.map { (label, action) -> button(label, action) }.toTypedArray())
        root.addView(row, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
    }

    /** Matches .logbox: rounded surface-container-high card, monospace, selectable output. */
    private fun outputView(root: LinearLayout, initial: String): TextView = ui.label(initial, 13f, R.color.md_on_surface_variant, mono = true).apply {
        setTextIsSelectable(true)
        setLineSpacing(dp(2).toFloat(), 1f)
        background = ui.pillBg(R.color.md_surface_container_high, 20)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        root.addView(this, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
    }
    private fun askText(title: String, done: (String) -> Unit) {
        val field = EditText(this).apply { hint = title; inputType = InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(this).setTitle(title).setView(field).setNegativeButton("Cancel", null)
            .setPositiveButton("OK") { _, _ -> field.text.toString().trim().takeIf { it.isNotBlank() }?.let(done) }.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Without this, auto-refresh screenshots keep firing via `handler` and the ADB
        // socket/read-thread in `adb` stay alive after the Activity is gone.
        screenAuto = false
        dismissToast()
        handler.removeCallbacksAndMessages(null)
        autoReconnect = false
        val old = adb
        old?.onClosed = null
        thread { runCatching { old?.close() } }
    }

    companion object {
        // Tab indices, matching the order in `tabNames` above.
        private const val TAB_PACKAGES = 0
        private const val TAB_PROCESSES = 1
        private const val TAB_COMMANDS = 2
        private const val TAB_REMOTE = 3
        private const val TAB_SCREEN = 4
        private const val TAB_FILES = 5

        private const val PREF_LAST_HOST = "last_host_5555"
        private const val RC_MARK = "__ADB_RC__"
        private const val REQUEST_SIDELOAD = 2004
        private const val REQUEST_PUSH_FILE = 2001
        private const val REQUEST_PULL_FILE = 2002
        private const val REQUEST_SAVE_SCREEN = 2003
    }
}


private class SimpleTextWatcher(private val changed: (String) -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changed(s?.toString().orEmpty()) }
    override fun afterTextChanged(s: android.text.Editable?) = Unit
}
