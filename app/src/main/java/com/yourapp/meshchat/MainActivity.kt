package com.yourapp.meshchat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputFilter
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private lateinit var bgImage: ImageView
    private lateinit var bgDim: View
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var messageInput: EditText
    private lateinit var messageList: RecyclerView
    private lateinit var adapter: MessageAdapter
    private lateinit var cmdName: TextView
    private lateinit var cmdWifi: TextView

    private val bgFile get() = File(filesDir, "bg.jpg")

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val missing = mandatoryPermissions().filter { !granted(it) }
        if (missing.isEmpty()) {
            ensureService()
            askBatteryExemption()
        } else {
            statusText.text = "meshchat:~$ error: missing permissions\n" + missing.joinToString("\n")
            Toast.makeText(this, "Bluetooth permissions are required", Toast.LENGTH_LONG).show()
        }
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) saveBackgroundImage(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ChatRepository.init(this)

        root = findViewById(R.id.root)
        bgImage = findViewById(R.id.bgImage)
        bgDim = findViewById(R.id.bgDim)
        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        messageInput = findViewById(R.id.messageInput)
        messageList = findViewById(R.id.messageList)
        cmdName = findViewById(R.id.cmdName)
        cmdWifi = findViewById(R.id.cmdWifi)
        val cmdBg: TextView = findViewById(R.id.cmdBg)
        val sendButton: TextView = findViewById(R.id.sendButton)

        adapter = MessageAdapter(mutableListOf()) { m -> confirmDeleteMessage(m) }
        messageList.layoutManager = LinearLayoutManager(this)
        messageList.adapter = adapter

        applyBackground()
        refreshCommands()

        cmdName.setOnClickListener { showNameDialog(first = false) }
        cmdBg.setOnClickListener { showBackgroundDialog() }
        cmdWifi.setOnClickListener { toggleWifi() }
        findViewById<TextView>(R.id.cmdShare).setOnClickListener { shareApp() }
        findViewById<TextView>(R.id.cmdClear).setOnClickListener { confirmClearAll() }

        sendButton.setOnClickListener {
            val text = messageInput.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            if (MeshService.sendText(this, text)) {
                messageInput.text.clear()
            } else {
                Toast.makeText(this, "mesh isn't running yet", Toast.LENGTH_SHORT).show()
            }
        }

        if (Prefs.name(this).isBlank()) showNameDialog(first = true)

        if (mandatoryPermissions().all { granted(it) }) {
            ensureService()
            askBatteryExemption()
        } else {
            statusText.text = "meshchat:~$ requesting permissions..."
            requestPermissions.launch(allPermissions())
        }
    }

    override fun onStart() {
        super.onStart()
        ChatRepository.uiVisible = true
        ChatRepository.clearPending()
        Notifier.cancelMessages(this)

        ChatRepository.onMessage = { m ->
            adapter.addMessage(m)
            messageList.scrollToPosition(adapter.itemCount - 1)
        }
        ChatRepository.onStatus = { updateStatusText() }

        adapter.setAll(ChatRepository.snapshot())
        if (adapter.itemCount > 0) messageList.scrollToPosition(adapter.itemCount - 1)
        updateStatusText()

        if (!MeshService.running && mandatoryPermissions().all { granted(it) }) ensureService()
    }

    override fun onStop() {
        super.onStop()
        // The mesh keeps running in MeshService; we only stop updating the screen.
        ChatRepository.uiVisible = false
        ChatRepository.onMessage = null
        ChatRepository.onStatus = null
    }

    private fun ensureService() {
        if (!MeshService.running) MeshService.start(this)
    }

    // ---------- terminal text ----------

    private fun updateStatusText() {
        val mode = if (Prefs.wifi(this)) "ble+wifi" else "ble"
        val peers = ChatRepository.peerCount()
        val names = ChatRepository.nearbyNames()
        val state = when {
            !MeshService.running -> "stopped (reopen app)"
            peers == 0 -> "scanning..."
            names.isEmpty() -> "$peers peer(s) connected"
            else -> "$peers peer(s): ${names.joinToString(", ")}"
        }
        statusText.text = "${Prefs.displayName(this)}@mesh:$mode$ $state"

        // Cosmetic boot-log: the last few transport events, dimmed
        logText.text = ChatRepository.debugSnapshot().joinToString("\n") { "> $it" }
    }

    private fun refreshCommands() {
        cmdName.text = "[name:${Prefs.displayName(this)}]"
        val on = Prefs.wifi(this)
        cmdWifi.text = if (on) "[wifi:on]" else "[wifi:off]"
        cmdWifi.setTextColor(if (on) MessageAdapter.GREEN else 0xFF7A7A7A.toInt())
    }

    // ---------- Wi-Fi toggle ----------

    private fun toggleWifi() {
        val enable = !Prefs.wifi(this)
        Prefs.setWifi(this, enable)
        val mgr = MeshService.manager
        if (mgr != null) {
            try {
                mgr.setWifiDirectEnabled(enable)
                if (enable) {
                    Toast.makeText(
                        this, "wifi direct on - accept the invite on the other phone", Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Prefs.setWifi(this, false)
                Toast.makeText(this, "wifi direct unavailable on this device", Toast.LENGTH_LONG).show()
            }
        }
        refreshCommands()
        updateStatusText()
    }

    // ---------- dialogs ----------

    private fun dialog() = AlertDialog.Builder(this, R.style.TerminalDialog)

    private fun styledInput(initial: String, hintText: String, maxLen: Int): EditText =
        EditText(this).apply {
            setText(initial)
            hint = hintText
            setSingleLine()
            filters = arrayOf(InputFilter.LengthFilter(maxLen))
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF5A5A5A.toInt())
            ResourcesCompat.getFont(this@MainActivity, R.font.pixel_font)?.let { typeface = it }
            setPadding(48, 32, 48, 32)
        }

    /** Shares this app's own APK through the system share sheet (pick Bluetooth there). */
    private fun shareApp() {
        try {
            val dir = File(cacheDir, "share").apply { mkdirs() }
            val apk = File(dir, "MeshChat.apk")
            File(applicationInfo.sourceDir).copyTo(apk, overwrite = true)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "send MeshChat via Bluetooth"))
        } catch (e: Exception) {
            Toast.makeText(this, "couldn't share the app: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmClearAll() {
        dialog()
            .setTitle("clear all chats?")
            .setMessage("this deletes the history on this phone only. other phones keep theirs.")
            .setPositiveButton("clear") { _, _ ->
                ChatRepository.clearAll()
                Notifier.cancelMessages(this)
                adapter.setAll(emptyList())
            }
            .setNegativeButton("cancel", null)
            .show()
    }

    private fun confirmDeleteMessage(m: ChatMessage) {
        dialog()
            .setTitle("delete this message?")
            .setMessage(m.text.take(120))
            .setPositiveButton("delete") { _, _ ->
                ChatRepository.remove(m)
                adapter.setAll(ChatRepository.snapshot())
            }
            .setNegativeButton("cancel", null)
            .show()
    }

    private fun showNameDialog(first: Boolean) {
        val input = styledInput(Prefs.name(this), "your name", 24)
        val builder = dialog()
            .setTitle(if (first) "choose your name" else "change your name")
            .setMessage("nearby people see this next to your messages")
            .setView(input)
            .setCancelable(!first)
            .setPositiveButton("save") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty()) {
                    Prefs.setName(this, n)
                    refreshCommands()
                    updateStatusText()
                    MeshService.announce(this)
                }
            }
        if (!first) builder.setNegativeButton("cancel", null)
        builder.show()
    }

    private fun showBackgroundDialog() {
        val presets = listOf(
            "black (default)" to 0xFF000000.toInt(),
            "dark gray" to 0xFF1A1A1A.toInt(),
            "navy" to 0xFF0A1128.toInt(),
            "dark green" to 0xFF04140A.toInt(),
            "deep purple" to 0xFF140A28.toInt()
        )
        val items = presets.map { it.first } + listOf("custom color (hex)...", "choose image...", "reset")
        dialog()
            .setTitle("terminal background")
            .setItems(items.toTypedArray()) { _, which ->
                when {
                    which < presets.size -> {
                        Prefs.setBgColor(this, presets[which].second)
                        applyBackground()
                    }
                    which == presets.size -> showHexDialog()
                    which == presets.size + 1 -> pickImage.launch("image/*")
                    else -> {
                        Prefs.setBgColor(this, 0xFF000000.toInt())
                        bgFile.delete()
                        applyBackground()
                    }
                }
            }
            .setNegativeButton("cancel", null)
            .show()
    }

    private fun showHexDialog() {
        val input = styledInput("", "#RRGGBB", 9)
        dialog()
            .setTitle("custom background color")
            .setView(input)
            .setPositiveButton("apply") { _, _ ->
                var hex = input.text.toString().trim()
                if (!hex.startsWith("#")) hex = "#$hex"
                try {
                    Prefs.setBgColor(this, Color.parseColor(hex) or 0xFF000000.toInt())
                    applyBackground()
                } catch (e: IllegalArgumentException) {
                    Toast.makeText(this, "invalid color, use e.g. #101820", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("cancel", null)
            .show()
    }

    // ---------- background ----------

    private fun applyBackground() {
        var barColor = Prefs.bgColor(this)
        var showedImage = false

        if (Prefs.bgIsImage(this) && bgFile.exists()) {
            val bmp = BitmapFactory.decodeFile(bgFile.absolutePath)
            if (bmp != null) {
                bgImage.setImageBitmap(bmp)
                bgImage.visibility = View.VISIBLE
                bgDim.visibility = View.VISIBLE // keeps white/green text readable on any picture
                barColor = Color.BLACK
                showedImage = true
            }
        }
        if (!showedImage) {
            bgImage.setImageDrawable(null)
            bgImage.visibility = View.GONE
            bgDim.visibility = View.GONE
        }

        root.setBackgroundColor(if (showedImage) Color.BLACK else Prefs.bgColor(this))
        window.statusBarColor = barColor
        window.navigationBarColor = barColor
        val light = ColorUtils.calculateLuminance(barColor) > 0.5
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    /** Copies the picked picture into app storage (downscaled) so it works after a restart. */
    private fun saveBackgroundImage(uri: Uri) {
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("cannot read image")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                ?: throw IllegalStateException("not an image")
            FileOutputStream(bgFile).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            Prefs.setBgImage(this, true)
            applyBackground()
        } catch (e: Exception) {
            Toast.makeText(this, "couldn't use that image", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- battery + permissions ----------

    /** Asks (once) to be exempt from battery optimisation so the mesh survives screen-off. */
    private fun askBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName) || Prefs.batteryAsked(this)) return
        Prefs.setBatteryAsked(this)
        dialog()
            .setTitle("keep running in the background")
            .setMessage(
                "allow meshchat to ignore battery optimisation so it keeps receiving " +
                    "messages when the screen is off."
            )
            .setPositiveButton("allow") { _, _ ->
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
            .setNegativeButton("not now", null)
            .show()
    }

    private fun granted(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Without these the mesh can't run at all. */
    private fun mandatoryPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** Everything we ask for; notifications and Wi-Fi Direct are optional. */
    private fun allPermissions(): Array<String> {
        val perms = mandatoryPermissions().toMutableList()
        if (!perms.contains(Manifest.permission.ACCESS_FINE_LOCATION)) {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION) // needed for Wi-Fi Direct discovery
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return perms.toTypedArray()
    }
}
