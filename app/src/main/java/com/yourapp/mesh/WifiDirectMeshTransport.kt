package com.yourapp.mesh

import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pManager.Channel

class WifiDirectMeshTransport(private val context: Context) : MeshTransport {

    override val name = "WiFiDirect"
    override var isActive = false
        private set

    private val manager =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private var channel: Channel? = null
    private var onMessage: ((MeshMessage) -> Unit)? = null
    private val peerCount = java.util.concurrent.atomic.AtomicInteger(0)

    override fun setDebugListener(listener: ((String) -> Unit)?) {
        // Not wired up yet — Wi-Fi Direct transport is still a skeleton
    }

    override fun setPeerConnectionListener(listener: ((address: String, connected: Boolean) -> Unit)?) {
        // Not wired up yet — Wi-Fi Direct transport is still a skeleton
    }

    override fun start(onMessageReceived: (MeshMessage) -> Unit) {
        onMessage = onMessageReceived
        channel = manager.initialize(context, context.mainLooper, null)
        discoverPeers()
        isActive = true
    }

    override fun stop() {
        channel?.let { manager.stopPeerDiscovery(it, null) }
        isActive = false
    }

    override fun send(message: MeshMessage) {
        // TODO: send over an established socket connection to a connected group peer
        // once WifiP2pManager.connect() has completed and a P2P group is formed.
        // Wi-Fi Direct has much higher bandwidth than BLE — use this for images/files.
    }

    override fun connectedPeerCount(): Int = peerCount.get()

    private fun discoverPeers() {
        val c = channel ?: return
        manager.discoverPeers(c, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { /* peers found via broadcast receiver, register separately */ }
            override fun onFailure(reason: Int) { /* handle retry/backoff */ }
        })
    }
}
