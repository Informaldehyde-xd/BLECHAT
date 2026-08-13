package com.yourapp.mesh

/**
 * Common interface both BLE and Wi-Fi Direct transports implement.
 * MeshManager talks only to this interface — never to BLE/WiFi APIs directly.
 */
interface MeshTransport {
    val name: String
    val isActive: Boolean

    fun start(onMessageReceived: (MeshMessage) -> Unit)
    fun stop()
    fun send(message: MeshMessage)
    fun connectedPeerCount(): Int

    // Lets UI show what's happening inside the transport without Logcat/adb
    fun setDebugListener(listener: ((String) -> Unit)?)

    // Fires when a peer connects or disconnects, so the UI can show a clean notice
    // instead of parsing the debug log.
    fun setPeerConnectionListener(listener: ((address: String, connected: Boolean) -> Unit)?)
}
