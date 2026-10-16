package com.example.tvremote

import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Intent
import android.database.Cursor
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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.tvremote.adb.AdbConnection
import com.example.tvremote.adb.AdbCrypto
import com.example.tvremote.adb.AdbSyncClient
import com.example.tvremote.adb.transport.TcpTransport
import com.example.tvremote.adb.transport.UsbTransport
import com.example.tvremote.ui.Ui
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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
    }

    private fun buildShellUi() {
        // Outer frame: scrollable content behind a floating pill nav bar, same layering as
        // #pages + .nav-bar in style.css (nav bar pinned to the bottom, not pushing content).
        val frame = FrameLayout(this).apply { setBackgroundColor(color(R.color.md_surface)) }

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
        val connectButton = TextView(this).apply {
            text = "Connect"
            textSize = 13.5f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setTextColor(color(R.color.md_on_primary))
            background = ui.pillBg(R.color.md_primary, 200)
            setOnClickListener { openConnectDialog() }
        }.withPressAnimation()
        topBar.addView(ipChip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        topBar.addView(connectButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        statusView = TextView(this).apply {
            text = "Not connected"
            textSize = 13f
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
            val item = TextView(this).apply {
                text = label
                textSize = 13.5f
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setOnClickListener { renderTab(index) }
            }
            tabsRow.addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginEnd = dp(2) })
        }
        frame.addView(page, FrameLayout.LayoutParams(-1, -1))
        frame.addView(
            navScroll,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(16)
            }
        )
        setContentView(frame)
        updateTabHighlight()
        updateConnectionChip()
    }

    private fun updateConnectionChip() {
        if (!::ipChip.isInitialized) return
        ipChip.text = if (adb != null) "$remoteIp:$remotePort" else ""
    }

    private fun renderTab(index: Int) {
        selectedTab = index
        updateTabHighlight()
        filePathEdit = null; fileListContainer = null; fileOutput = null; packageFilter = null; processFilter = null; screenImage = null
        content.removeAllViews()
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Extra bottom padding so content can scroll clear of the floating pill nav bar.
            setPadding(dp(16), dp(14), dp(16), dp(96))
        }
        scroll.addView(body)
        content.addView(scroll)
        when (index) {
            TAB_PACKAGES -> buildPackagesTab(body)
            TAB_PROCESSES -> buildProcessesTab(body)
            TAB_COMMANDS -> buildCommandsTab(body)
            TAB_REMOTE -> buildRemoteTab(body)
            TAB_SCREEN -> buildScreenTab(body)
            TAB_FILES -> buildFilesTab(body)
        }
        // Same entrance as @keyframes rise in style.css: translateY(20px)+opacity 0 -> 0/1, .25s.
        scroll.translationY = dp(20).toFloat()
        scroll.alpha = 0f
        scroll.animate().translationY(0f).alpha(1f).setDuration(250)
            .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f)).start()
        if (index == TAB_SCREEN && screenAuto) handler.post { scheduleScreenCapture() }
    }

    private fun updateTabHighlight() {
        if (!::tabsRow.isInitialized) return
        for (i in 0 until tabsRow.childCount) {
            val view = tabsRow.getChildAt(i) as TextView
            val active = i == selectedTab
            view.background = if (active) ui.pillBg(R.color.md_secondary_container, 200) else null
            view.setTextColor(color(if (active) R.color.md_on_secondary_container else R.color.md_on_surface_variant))
            view.setTypeface(null, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
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
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }

        if (adb != null) {
            container.addView(ui.label("Connected to $remoteIp:$remotePort", 14f, R.color.md_on_surface, bold = true), matchWrap())
            container.addView(ui.pill("Disconnect") {
                thread { runCatching { adb?.close() }; adb = null; runOnUiThread { updateConnectionChip(); setStatus("Disconnected") } }
            }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8); bottomMargin = dp(8) })
            divider(container)
        }

        container.addView(ui.label("Wireless debugging", 14f, R.color.md_on_surface, bold = true), matchWrap())

        val scanStatus = ui.label("Scanning the local network for port 5555...", 13f, R.color.md_on_surface_variant)
        container.addView(scanStatus, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        val suggestions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(suggestions, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6); bottomMargin = dp(6) })

        // IP only - no port field. Normal Wireless Debugging devices listen on 5555, so that's
        // always what's used to connect; no need to ask for it.
        val wirelessField = edit(container, "TV IP address", remoteIp, InputType.TYPE_CLASS_TEXT)

        val cancelled = AtomicBoolean(false)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Connect to TV")
            .setView(ScrollView(this).apply { addView(container) })
            .setNegativeButton("Close", null)
            .setOnDismissListener { cancelled.set(true) }
            .create()

        container.addView(ui.pill("Connect", primary = true) {
            val host = wirelessField.text.toString().trim()
            if (host.isBlank()) { toast("Enter the TV's IP address"); return@pill }
            remoteIp = host; remotePort = 5555
            connectNetwork(host, 5555)
            dialog.dismiss()
        }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4); bottomMargin = dp(8) })

        divider(container)
        container.addView(ui.label("Pair a new device (Wireless debugging)", 14f, R.color.md_on_surface, bold = true), matchWrap())
        val pairIp = edit(container, "Pairing IP:port (shown on the TV)", "", InputType.TYPE_CLASS_TEXT)
        val pairCode = edit(container, "6-digit pairing code", "", InputType.TYPE_CLASS_NUMBER)
        container.addView(ui.label(
            "Pairing uses a different, TLS-based handshake from a normal ADB connection and isn't implemented in this build yet. Pair the TV from its own Settings first, then use the field above with the IP it ends up listening on.",
            12.5f, R.color.md_on_surface_variant
        ).apply { setPadding(0, dp(2), 0, dp(6)) }, matchWrap())
        container.addView(ui.pill("Pair") {
            if (pairIp.text.toString().isBlank() || pairCode.text.toString().isBlank()) { toast("Enter the pairing IP:port and the 6-digit code"); return@pill }
            toast("Pairing isn't implemented yet - pair from the TV's Settings first, then Connect above.")
        }, matchWrap())

        dialog.show()

        // Any suggested IP found listening on 5555 connects to that device immediately on tap.
        scanForAdbDevices(
            onFound = { ip ->
                if (cancelled.get()) return@scanForAdbDevices
                suggestions.addView(ui.pill(ip) {
                    wirelessField.setText(ip)
                    remoteIp = ip; remotePort = 5555
                    connectNetwork(ip, 5555)
                    dialog.dismiss()
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
            var found = 0
            for (i in 1..254) {
                pool.execute {
                    try {
                        Socket().use { s -> s.connect(InetSocketAddress("$prefix$i", 5555), 250) }
                        found++
                        runOnUiThread { onFound("$prefix$i") }
                    } catch (_: Exception) {
                        // host down or port closed - expected for most of the subnet
                    } finally { latch.countDown() }
                }
            }
            latch.await(6, TimeUnit.SECONDS)
            pool.shutdownNow()
            runOnUiThread { onDone(found) }
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
        title(root, "TV remote")
        paragraph(root, "Use the Connect button at the top to pair with a TV over Wi-Fi or USB.")
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

    private fun connectNetwork(host: String, port: Int) {
        if (host.isBlank()) { toast("Enter the TV's IP address"); return }
        setStatus("Connecting to $host:$port...")
        thread(name = "adb-connect-tcp") {
            try {
                val connection = AdbConnection(TcpTransport(host, port), crypto)
                connection.connect()
                adb?.close(); adb = connection
                runOnUiThread { updateConnectionChip(); setStatus("Connected - $host:$port") }
            } catch (e: Exception) {
                runOnUiThread { setStatus("Connection failed: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    private fun connectUsb() {
        val manager = getSystemService(USB_SERVICE) as UsbManager
        val device: UsbDevice? = manager.deviceList.values.firstOrNull { UsbTransport.findAdbInterface(it) != null }
        if (device == null) { toast("No USB device with an ADB interface found. Check the OTG cable and USB debugging."); return }
        if (!manager.hasPermission(device)) {
            val pi = PendingIntent.getBroadcast(this, 0, Intent("com.example.tvremote.USB_PERMISSION"), PendingIntent.FLAG_MUTABLE)
            manager.requestPermission(device, pi)
            toast("Grant the USB permission, then tap Connect USB again")
            return
        }
        setStatus("Connecting over USB...")
        thread(name = "adb-connect-usb") {
            try {
                val connection = AdbConnection(UsbTransport(manager, device), crypto)
                connection.connect(); adb?.close(); adb = connection
                runOnUiThread { updateConnectionChip(); setStatus("Connected over USB") }
            } catch (e: Exception) { runOnUiThread { setStatus("USB connection failed: ${e.message}") } }
        }
    }

    private fun sendKey(code: Int) = runShell("input keyevent $code")

    // ─────────────────────────────────── Commands ───────────────────────────────────
    private data class Builtin(val name: String, val command: String?, val note: String, val confirm: Boolean = false, val action: String? = null)

    private fun buildCommandsTab(root: LinearLayout) {
        title(root, "Commands")
        val packageInput = edit(root, "Package for the force-stop command", "", InputType.TYPE_CLASS_TEXT)
        val commands = listOf(
            Builtin("01 - Location status", "dumpsys location", "Show location services"),
            Builtin("02 - Disable Developer options flag", "settings put global development_settings_enabled 0", "Changes a system setting", true),
            Builtin("03 - Open Screen tab", null, "Open the screenshot tab", action = "screen"),
            Builtin("04 - Reboot", "reboot", "Reboot the device", true),
            Builtin("05 - Reboot to bootloader", "reboot bootloader", "Only works on supported devices", true),
            Builtin("06 - Power off", "reboot -p", "Not every device supports this", true),
            Builtin("07 - Read screen orientation", "settings get system user_rotation", "Read the current rotation value"),
            Builtin("08 - Force landscape", "settings put system accelerometer_rotation 0; settings put system user_rotation 1", "Disables auto-rotate and sets orientation 1", true),
            Builtin("09 - Force portrait", "settings put system accelerometer_rotation 0; settings put system user_rotation 0", "Disables auto-rotate and sets orientation 0", true),
            Builtin("10 - Open Developer options", "am start -a com.android.settings.APPLICATION_DEVELOPMENT_SETTINGS", "The activity name can differ between ROMs"),
            Builtin("11 - Enable auto-rotate", "settings put system accelerometer_rotation 1", "Turns auto-rotate back on", true),
            Builtin("12 - Disable auto-rotate", "settings put system accelerometer_rotation 0", "Turns auto-rotate off", true),
            Builtin("13 - Set debug refresh rate to 90", "setprop debug.oculus.refreshRate 90", "Debug property; does not guarantee a 90 Hz panel", true),
            Builtin("14 - ADB device info", "getprop ro.serialno; getprop ro.product.model; getprop ro.build.version.release", "Reads serial, model and Android version"),
            Builtin("15 - Active windows", "dumpsys window windows", "System window information"),
            Builtin("16 - Force-stop a package", "am force-stop {pkg}", "Enter a package in the field above", true),
            Builtin("17 - Kill eligible background processes", "am kill-all", "Android may skip some processes", true),
            Builtin("18 - WLAN IP address", "ip -f inet addr show wlan0", "Wi-Fi network information"),
            Builtin("19 - WLAN IPv4 address only", "ip -f inet addr show wlan0 | grep 'inet '", "May differ on devices with another interface name"),
            Builtin("20 - List /system/bin", "ls /system/bin", "List system commands"),
            Builtin("21 - Status bar transaction 1", "service call statusbar 1", "Binder call; behavior depends on Android version", true),
            Builtin("22 - Status bar transaction 2", "service call statusbar 2", "Binder call; behavior depends on Android version", true),
            Builtin("23 - Settings (global)", "settings list global", "List global settings"),
            Builtin("24 - Settings (secure)", "settings list secure", "List secure settings"),
            Builtin("25 - Settings (system)", "settings list system", "List system settings"),
            Builtin("26 - List permissions", "pm list permissions", "List Android permissions")
        )
        val rows = commands.map { item ->
            val handler: () -> Unit = handler@{
                if (item.action == "screen") { renderTab(TAB_SCREEN); return@handler }
                var command = item.command ?: return@handler
                if ("{pkg}" in command) {
                    val pkg = packageInput.text.toString().trim()
                    if (pkg.isBlank() || !pkg.matches(Regex("[A-Za-z0-9_.]+"))) { toast("Enter a valid package name first"); return@handler }
                    command = command.replace("{pkg}", shellQuote(pkg))
                }
                val cmd = command
                val execute = { runShell(cmd) { result -> showOutputDialog(cmd, result) } }
                if (item.confirm) AlertDialog.Builder(this).setTitle("Confirm command")
                    .setMessage("${item.note}\n\n$cmd")
                    .setNegativeButton("Cancel", null).setPositiveButton("Run") { _, _ -> execute() }.show()
                else execute()
            }
            ui.Row(item.name, item.note, onClick = handler)
        }
        root.addView(ui.group(rows), matchWrap())
        divider(root)
        title(root, "Custom command")
        val custom = edit(root, "Enter a shell command", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        row(root, listOf(
            "Run" to { val cmd = custom.text.toString().trim(); if (cmd.isNotEmpty()) runShell(cmd) { result -> showOutputDialog(cmd, result) } },
            "Device info" to { runShell("getprop; echo '=== BATTERY ==='; dumpsys battery; echo '=== RAM ==='; cat /proc/meminfo; echo '=== STORAGE ==='; df -h /data") { result -> showOutputDialog("device info", result) } },
            "Recent logcat" to { runShell("logcat -d -t 500") { result -> showOutputDialog("logcat -d -t 500", result) } }
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
        listOf("All" to 0, "Enabled" to 1, "Disabled" to 2).forEach { (label, value) ->
            stateRow.addView(
                ui.pill(label, primary = packageStateFilter == value) { packageStateFilter = value; renderTab(TAB_PACKAGES) }.withPressAnimation(),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
            )
        }
        root.addView(stateRow, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        row(root, listOf(
            (if (packageShowSystem) "User apps only" else "Include system apps") to { packageShowSystem = !packageShowSystem; renderTab(TAB_PACKAGES) },
            "Refresh" to { refreshPackages(root) }
        ))
        packageFilter = edit(root, "Filter by package name", "", InputType.TYPE_CLASS_TEXT)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list, matchWrap())
        val result = outputView(root, "Waiting for the package list...")
        packageFilter?.addTextChangedListener(SimpleTextWatcher { filter ->
            list.removeAllViews()
            renderPackageRows(list, filter, result)
        })
        refreshPackages(root) { list.removeAllViews(); renderPackageRows(list, packageFilter?.text?.toString().orEmpty(), result) }
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
        raw.lineSequence().map { it.trim().removePrefix("package:") }
            .filter { it.isNotBlank() && it.contains(filter, ignoreCase = true) }.distinct().sorted().toList()

    /** User apps first, system apps after - each group sorted alphabetically within itself. */
    private fun renderPackageRows(list: LinearLayout, filter: String, result: TextView) {
        val userPkgs = parsePackageList(packageRawUser, filter)
        val systemPkgs = if (packageShowSystem) parsePackageList(packageRawSystem, filter) else emptyList()
        val packages = userPkgs + systemPkgs
        result.text = "${packages.size} packages shown."
        val cards = packages.map { pkg -> buildPackageCard(pkg, list) }
        list.addView(chainedGroup(cards), matchWrap())
    }

    /** One package "card": app name + package name, then small action buttons underneath -
     *  matches the pm/APK flow described in the notes you sent (pm path, pull the APK,
     *  getPackageArchiveInfo for the real icon/label, pm enable/disable-user, am force-stop). */
    private fun buildPackageCard(pkg: String, listOwner: LinearLayout): LinearLayout {
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
        val actionsTop = ui.actionRow(
            ui.pill("Launch") { runShell("monkey -p ${shellQuote(pkg)} -c android.intent.category.LAUNCHER 1") { setStatus(it.ifBlank { "Launch request sent for $pkg" }) } },
            ui.pill("Stop") { runShell("am force-stop ${shellQuote(pkg)}") { setStatus(it.ifBlank { "Force-stop sent for $pkg" }) } }
        )
        val actionsBottom = ui.actionRow(
            ui.pill("Enable") { runShell("pm enable ${shellQuote(pkg)}") { setStatus(it.ifBlank { "Enable sent for $pkg" }) } },
            ui.pill("Disable") {
                AlertDialog.Builder(this).setTitle("Disable $pkg?").setMessage("Disabling a system package can break TV features until re-enabled.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Disable") { _, _ ->
                        runShell("pm disable-user --user 0 ${shellQuote(pkg)}") { setStatus(it.ifBlank { "Disable sent for $pkg" }) }
                    }.show()
            },
            ui.pill("Uninstall") {
                AlertDialog.Builder(this).setTitle("Uninstall $pkg?").setMessage("Uninstall $pkg for the current user on the target device?")
                    .setNegativeButton("Cancel", null).setPositiveButton("Uninstall") { _, _ ->
                        runShell("pm uninstall --user 0 ${shellQuote(pkg)}") { setStatus(it); refreshPackages(listOwner) }
                    }.show()
            }
        )
        // Tools stay collapsed until the card header (icon + name) is tapped.
        actionsTop.visibility = View.GONE
        actionsBottom.visibility = View.GONE
        header.setOnClickListener {
            val expanded = actionsTop.visibility == View.VISIBLE
            actionsTop.visibility = if (expanded) View.GONE else View.VISIBLE
            actionsBottom.visibility = if (expanded) View.GONE else View.VISIBLE
        }
        card.addView(actionsTop, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        card.addView(actionsBottom, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        loadPackageInfo(pkg, nameView, icon)
        return card
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
        paragraph(root, "Main source: ps -A. Columns and access can differ by Android version/ROM. Live CPU usage isn't computed in this build.")
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
        return search(content)
    }

    // ─────────────────────────────────── Screen capture / polling mirror ───────────────────────────────────
    private fun buildScreenTab(root: LinearLayout) {
        title(root, "Screen")
        paragraph(root, "This mode polls screencap periodically; it is not a low-latency scrcpy video stream. Requires screencap/base64 on the target device.")
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
        paragraph(root, "Browse directories, create/delete folders and transfer files over ADB SYNC. Access is still governed by Android.")
        val path = edit(root, "Path on the TV", "/sdcard", InputType.TYPE_CLASS_TEXT)
        filePathEdit = path
        row(root, listOf(
            "List" to { listRemoteFiles(path.text.toString()) },
            "Up to parent" to { path.setText(parentPath(path.text.toString())); listRemoteFiles(path.text.toString()) },
            "New folder" to { askText("New folder name") { name -> runShell("mkdir -p ${shellQuote(joinRemote(path.text.toString(), name))}") { setStatus(it.ifBlank { "Create-folder command sent" }); listRemoteFiles(path.text.toString()) } } }
        ))
        row(root, listOf(
            "Push file to TV" to { pendingPushPath = path.text.toString().trim().ifBlank { "/sdcard" }; pickLocalFile() },
            "Pull path from TV" to { askText("File path on the TV") { remote -> pendingPullPath = remote; createLocalFile(remote) } }
        ))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list, matchWrap())
        val output = outputView(root, "Tap List to open a directory")
        fileListContainer = list
        fileOutput = output
        listRemoteFiles(path.text.toString())
    }

    private fun listRemoteFiles(path: String, done: ((List<String>) -> Unit)? = null) {
        val safe = path.trim().ifBlank { "/sdcard" }
        runShell("ls -1Ap ${shellQuote(safe)}") { raw ->
            val entries = raw.lineSequence().map { it.trimEnd('\r') }.filter { it.isNotBlank() && it != "Permission denied" && !it.startsWith("ls: ") }.toList()
            if (done != null) done(entries) else renderFileEntries(entries)
        }
    }

    private fun renderFileEntries(entries: List<String>) {
        val list = fileListContainer ?: return
        val output = fileOutput ?: return
        val path = filePathEdit ?: return
        list.removeAllViews()
        output.text = if (entries.isEmpty()) "Empty directory or no read permission." else "${entries.size} items in ${path.text}"
        val rows = entries.map { entry ->
            val isDir = entry.endsWith("/")
            val name = entry.removeSuffix("/")
            ui.Row(entry, onClick = {
                val full = joinRemote(path.text.toString(), name)
                if (isDir) { path.setText(full); listRemoteFiles(full) }
                else showFileActions(full, output) { listRemoteFiles(path.text.toString()) }
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
                    .setPositiveButton("Delete") { _, _ -> runShell("rm -f ${shellQuote(remotePath)}") { output.text = it.ifBlank { "Delete command sent" }; refreshed() } }.show()
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

    private fun requireAdb(): AdbConnection = adb ?: throw IOException("Not connected")
    private fun setStatus(value: String) { statusView.text = value }
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()
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
        handler.removeCallbacksAndMessages(null)
        thread { runCatching { adb?.close() } }
    }

    companion object {
        // Tab indices, matching the order in `tabNames` above.
        private const val TAB_PACKAGES = 0
        private const val TAB_PROCESSES = 1
        private const val TAB_COMMANDS = 2
        private const val TAB_REMOTE = 3
        private const val TAB_SCREEN = 4
        private const val TAB_FILES = 5

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
