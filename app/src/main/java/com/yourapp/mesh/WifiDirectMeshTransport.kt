package com.yourapp.mesh

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pManager.Channel
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wi-Fi Direct transport.
 *
 * Flow:
 *  1. Advertise a DNS-SD service ("blechat") and discover peers/services, so we only
 *     ever try to connect to other phones running this app (not TVs, printers, etc).
 *  2. The device with the lower P2P address initiates WifiP2pManager.connect()
 *     (avoids both sides connecting at once).
 *  3. When the group forms, the Group Owner opens a TCP ServerSocket and clients
 *     connect to it. Messages are length-prefixed MeshCodec frames over that socket.
 *
 * Note: a Wi-Fi Direct group is a star (clients <-> owner), and a phone can only be in
 * one group at a time. MeshManager's TTL relay forwards messages between clients via the owner.
 */
@SuppressLint("MissingPermission")
class WifiDirectMeshTransport(private val context: Context) : MeshTransport {

    companion object {
        private const val PORT = 8988
        private const val SERVICE_NAME = "blechat"
        private const val SERVICE_TYPE = "_blechat._tcp"
        private const val MAX_FRAME = 1_000_000
        private const val REDISCOVER_MS = 30_000L
        private const val CONNECT_TIMEOUT_MS = 20_000L
    }

    override val name = "WiFiDirect"

    @Volatile
    override var isActive = false
        private set

    private val manager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private var channel: Channel? = null
    private var onMessage: ((MeshMessage) -> Unit)? = null
    private var debugListener: ((String) -> Unit)? = null
    private var peerConnectionListener: ((String, Boolean) -> Unit)? = null

    private val running = AtomicBoolean(false)
    private val connecting = AtomicBoolean(false)
    private val clientBusy = AtomicBoolean(false)
    private val serverRunning = AtomicBoolean(false)

    @Volatile private var myAddress: String? = null
    @Volatile private var isGroupOwner = false
    @Volatile private var serverSocket: ServerSocket? = null
    private var receiverRegistered = false

    private val appPeers = java.util.Collections.synchronizedSet(HashSet<String>())
    private val peers = CopyOnWriteArrayList<Peer>()

    private var ioPool: ExecutorService? = null
    private var writer: ExecutorService? = null

    // ---- MeshTransport API -------------------------------------------------

    override fun setDebugListener(listener: ((String) -> Unit)?) {
        debugListener = listener
    }

    override fun setPeerConnectionListener(listener: ((address: String, connected: Boolean) -> Unit)?) {
        peerConnectionListener = listener
    }

    private fun debug(msg: String) {
        debugListener?.invoke("[WiFiDirect] $msg")
    }

    override fun start(onMessageReceived: (MeshMessage) -> Unit) {
        if (running.get()) return
        val m = manager ?: throw IllegalStateException("Wi-Fi Direct not supported")

        onMessage = onMessageReceived
        running.set(true)
        ioPool = Executors.newCachedThreadPool()
        writer = Executors.newSingleThreadExecutor()

        channel = m.initialize(context, Looper.getMainLooper(), null)
        registerReceiver()
        setupServiceDiscovery()
        startDiscovery()
        scheduleRediscovery()
        isActive = true
        debug("Transport started")
    }

    override fun stop() {
        if (!running.getAndSet(false)) return
        isActive = false
        mainHandler.removeCallbacksAndMessages(null)

        if (receiverRegistered) {
            try { context.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) {}
            receiverRegistered = false
        }

        val m = manager
        val c = channel
        if (m != null && c != null) {
            try {
                m.stopPeerDiscovery(c, null)
                m.clearServiceRequests(c, null)
                m.clearLocalServices(c, null)
                m.cancelConnect(c, null)
                m.removeGroup(c, null)
            } catch (_: Exception) {}
            if (Build.VERSION.SDK_INT >= 27) {
                try { c.close() } catch (_: Exception) {}
            }
        }
        channel = null

        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null
        serverRunning.set(false)
        peers.toList().forEach { it.close() }
        // reader threads call removePeer() as sockets close; make sure the list is clean anyway
        peers.clear()

        ioPool?.shutdownNow()
        writer?.shutdownNow()
        ioPool = null
        writer = null
        connecting.set(false)
        clientBusy.set(false)
        isGroupOwner = false
        appPeers.clear()
        debug("Transport stopped")
    }

    override fun send(message: MeshMessage) {
        if (!running.get()) return
        if (peers.isEmpty()) {
            debug("send() called but no connected peers")
            return
        }
        val bytes = MeshCodec.encode(message)
        try {
            writer?.execute {
                var sent = 0
                for (p in peers) {
                    try {
                        p.write(bytes)
                        sent++
                    } catch (e: IOException) {
                        debug("Write failed to ${p.address}: ${e.message}")
                        removePeer(p)
                    }
                }
                debug("Sent to $sent peer(s)")
            }
        } catch (_: RejectedExecutionException) {
            // transport is shutting down
        }
    }

    override fun connectedPeerCount(): Int = peers.size

    // ---- Discovery ---------------------------------------------------------

    private fun action(ok: String?, fail: String, onFail: (() -> Unit)? = null) =
        object : WifiP2pManager.ActionListener {
            override fun onSuccess() { ok?.let { debug(it) } }
            override fun onFailure(reason: Int) {
                debug("$fail (reason=$reason)")
                onFail?.invoke()
            }
        }

    private fun setupServiceDiscovery() {
        val m = manager ?: return
        val c = channel ?: return

        // Advertise ourselves so other copies of the app can find us
        val info = WifiP2pDnsSdServiceInfo.newInstance(SERVICE_NAME, SERVICE_TYPE, emptyMap())
        m.clearLocalServices(c, null)
        m.addLocalService(c, info, action("Advertising service", "Advertise failed"))

        // Only peers that answer with our service name are treated as app peers
        m.setDnsSdResponseListeners(
            c,
            { instanceName, _, srcDevice ->
                if (instanceName == SERVICE_NAME) {
                    debug("Found app peer ${srcDevice.deviceAddress}")
                    appPeers.add(srcDevice.deviceAddress)
                    requestPeers()
                }
            },
            { _, _, _ -> }
        )
        m.clearServiceRequests(c, null)
        m.addServiceRequest(
            c, WifiP2pDnsSdServiceRequest.newInstance(),
            action("Service request added", "Service request failed")
        )
    }

    private fun startDiscovery() {
        val m = manager ?: return
        val c = channel ?: return
        try {
            m.discoverPeers(c, action("Peer discovery started", "Peer discovery failed"))
            m.discoverServices(c, action("Service discovery started", "Service discovery failed"))
        } catch (e: SecurityException) {
            debug("Missing permission for Wi-Fi Direct discovery: ${e.message}")
        }
    }

    private fun scheduleRediscovery() {
        mainHandler.postDelayed({
            if (!running.get()) return@postDelayed
            if (peers.isEmpty() && !connecting.get()) startDiscovery()
            scheduleRediscovery()
        }, REDISCOVER_MS)
    }

    // ---- Broadcasts --------------------------------------------------------

    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        debug("Wi-Fi P2P enabled")
                        startDiscovery()
                    } else {
                        debug("Wi-Fi P2P disabled — turn Wi-Fi on")
                    }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val m = manager ?: return
                    val ch = channel ?: return
                    m.requestConnectionInfo(ch) { info -> onConnectionInfo(info) }
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    myAddress = thisDevice(intent)?.deviceAddress
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun thisDevice(intent: Intent): WifiP2pDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
        } else {
            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
        }

    // ---- Connecting --------------------------------------------------------

    private fun requestPeers() {
        val m = manager ?: return
        val c = channel ?: return
        if (!running.get()) return
        try {
            m.requestPeers(c) onPeers@{ list ->
                if (connecting.get() || (!isGroupOwner && peers.isNotEmpty())) return@onPeers
                val target = list.deviceList.firstOrNull {
                    it.status == WifiP2pDevice.AVAILABLE &&
                        appPeers.contains(it.deviceAddress) &&
                        shouldInitiate(it)
                }
                if (target != null) connectTo(target)
            }
        } catch (e: SecurityException) {
            debug("Missing permission for requestPeers: ${e.message}")
        }
    }

    /** Lower P2P address initiates, so two phones don't connect to each other simultaneously. */
    private fun shouldInitiate(peer: WifiP2pDevice): Boolean {
        val mine = myAddress
        if (mine.isNullOrEmpty()) return true
        return mine < peer.deviceAddress
    }

    private fun connectTo(device: WifiP2pDevice) {
        val m = manager ?: return
        val c = channel ?: return
        if (!connecting.compareAndSet(false, true)) return

        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
        }
        debug("Connecting to ${device.deviceAddress}")
        try {
            m.connect(c, config, action("Connect request sent", "Connect failed") {
                connecting.set(false)
            })
        } catch (e: SecurityException) {
            connecting.set(false)
            debug("Missing permission for connect: ${e.message}")
            return
        }
        // If the other phone never accepts the invitation, allow retrying later
        mainHandler.postDelayed({ connecting.set(false) }, CONNECT_TIMEOUT_MS)
    }

    private fun onConnectionInfo(info: WifiP2pInfo) {
        if (!running.get()) return
        if (!info.groupFormed) {
            isGroupOwner = false
            connecting.set(false)
            return
        }
        connecting.set(false)
        if (info.isGroupOwner) {
            isGroupOwner = true
            debug("Group formed — I am the group owner")
            startServer()
        } else {
            isGroupOwner = false
            val host = info.groupOwnerAddress?.hostAddress ?: return
            debug("Group formed — joining owner at $host")
            connectToOwner(host)
        }
    }

    // ---- Sockets -----------------------------------------------------------

    private fun startServer() {
        if (!serverRunning.compareAndSet(false, true)) return
        ioPool?.execute {
            try {
                val ss = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PORT))
                }
                serverSocket = ss
                debug("Server listening on $PORT")
                while (running.get()) {
                    addPeer(ss.accept())
                }
            } catch (e: IOException) {
                if (running.get()) debug("Server stopped: ${e.message}")
            } finally {
                serverSocket = null
                serverRunning.set(false)
            }
        }
    }

    private fun connectToOwner(host: String) {
        if (peers.any { it.address == host }) return
        if (!clientBusy.compareAndSet(false, true)) return
        ioPool?.execute {
            try {
                for (attempt in 1..6) {
                    if (!running.get()) break
                    try {
                        val socket = Socket()
                        socket.connect(InetSocketAddress(host, PORT), 5_000)
                        addPeer(socket)
                        break
                    } catch (e: IOException) {
                        debug("Socket connect attempt $attempt failed: ${e.message}")
                        try { Thread.sleep(1_500) } catch (_: InterruptedException) { break }
                    }
                }
            } finally {
                clientBusy.set(false)
            }
        }
    }

    private fun addPeer(socket: Socket) {
        val peer = try {
            Peer(socket)
        } catch (e: IOException) {
            try { socket.close() } catch (_: IOException) {}
            return
        }
        peers.add(peer)
        debug("Peer connected ${peer.address}")
        peerConnectionListener?.invoke(peer.address, true)

        ioPool?.execute {
            try {
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                while (running.get()) {
                    val len = input.readInt()
                    if (len <= 0 || len > MAX_FRAME) break
                    val buf = ByteArray(len)
                    input.readFully(buf)
                    MeshCodec.decode(buf)?.let { onMessage?.invoke(it) }
                }
            } catch (_: IOException) {
                // connection closed
            } finally {
                removePeer(peer)
            }
        }
    }

    private fun removePeer(peer: Peer) {
        peer.close()
        if (!peers.remove(peer)) return
        debug("Peer disconnected ${peer.address}")
        peerConnectionListener?.invoke(peer.address, false)
        // A client that lost its owner leaves the group so it can discover/join again
        if (running.get() && !isGroupOwner && peers.isEmpty()) {
            val m = manager
            val c = channel
            if (m != null && c != null) m.removeGroup(c, null)
            mainHandler.post { if (running.get()) startDiscovery() }
        }
    }

    private class Peer(val socket: Socket) {
        val address: String = socket.inetAddress?.hostAddress ?: "unknown"
        private val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

        fun write(bytes: ByteArray) {
            synchronized(out) {
                out.writeInt(bytes.size)
                out.write(bytes)
                out.flush()
            }
        }

        fun close() {
            try { socket.close() } catch (_: IOException) {}
        }
    }
}
