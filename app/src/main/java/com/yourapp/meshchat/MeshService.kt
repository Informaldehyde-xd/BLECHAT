package com.yourapp.meshchat

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import com.yourapp.mesh.MeshManager
import com.yourapp.mesh.MeshMessage
import com.yourapp.mesh.MessageType
import java.nio.charset.StandardCharsets

/**
 * Foreground service that owns the mesh, so chat keeps working with the app closed
 * or the screen off. Incoming messages raise a notification with an inline Reply box.
 */
class MeshService : Service() {

    companion object {
        private const val PRESENCE_INTERVAL_MS = 45_000L

        @Volatile var manager: MeshManager? = null
        @Volatile var running = false

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, MeshService::class.java))
        }

        /** Sends a chat message from this phone and records it in the history. */
        fun sendText(ctx: Context, text: String): Boolean {
            val mgr = manager ?: return false
            val msg = MeshMessage(
                senderId = Prefs.nodeId(ctx),
                senderName = Prefs.displayName(ctx),
                type = MessageType.TEXT,
                payload = text.toByteArray(StandardCharsets.UTF_8)
            )
            mgr.sendMessage(msg)
            ChatRepository.add(ChatMessage(msg.senderName, text, isMine = true))
            return true
        }

        /** Tell nearby phones our (possibly new) name. */
        fun announce(ctx: Context) {
            val mgr = manager ?: return
            mgr.sendMessage(
                MeshMessage(
                    senderId = Prefs.nodeId(ctx),
                    senderName = Prefs.displayName(ctx),
                    ttl = 0, // direct neighbours only, never relayed
                    type = MessageType.PRESENCE,
                    payload = ByteArray(0)
                )
            )
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val presenceLoop = object : Runnable {
        override fun run() {
            announce(this@MeshService)
            handler.postDelayed(this, PRESENCE_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ChatRepository.init(this)
        Notifier.createChannels(this)

        try {
            val n = Notifier.foreground(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(Notifier.ID_FG, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(Notifier.ID_FG, n)
            }
        } catch (e: Exception) {
            // Missing Bluetooth permission etc. — can't run as a foreground service
            stopSelf()
            return
        }

        val mgr = MeshManager(applicationContext)
        manager = mgr

        mgr.onMessageReceived { msg -> handleIncoming(msg) }
        mgr.setDebugListener { ChatRepository.addDebug(it) }
        mgr.setPeerConnectionListener { address, connected ->
            ChatRepository.setPeerConnected(address, connected)
            Notifier.updateForeground(this)
            // Give the link a moment to finish setting up, then introduce ourselves
            if (connected) handler.postDelayed({ announce(this) }, 2_000)
        }

        try {
            mgr.start(enableWifiDirect = Prefs.wifi(this))
        } catch (e: Exception) {
            Prefs.setWifi(this, false) // Wi-Fi Direct unsupported — BLE still runs
            ChatRepository.addDebug("Wi-Fi Direct unavailable: ${e.message}")
        }

        running = true
        handler.postDelayed(presenceLoop, 3_000)
    }

    private fun handleIncoming(msg: MeshMessage) {
        val name = msg.senderName.ifBlank { "User-" + msg.senderId.take(4) }
        when (msg.type) {
            MessageType.PRESENCE -> {
                ChatRepository.seenPeer(msg.senderId, name)
                Notifier.updateForeground(this)
            }
            MessageType.TEXT -> {
                ChatRepository.seenPeer(msg.senderId, name)
                val text = String(msg.payload, StandardCharsets.UTF_8)
                ChatRepository.add(ChatMessage(name, text, isMine = false, time = msg.timestamp))
                if (!ChatRepository.uiVisible) Notifier.showMessages(this)
            }
            else -> Unit
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == Notifier.ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        manager?.stop()
        manager = null
        running = false
        ChatRepository.clearPeers()
        super.onDestroy()
    }
}
