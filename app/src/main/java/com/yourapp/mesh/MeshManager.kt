package com.yourapp.mesh

import android.content.Context
import java.util.Collections

/**
 * Single entry point the rest of the app talks to.
 * Runs one or more transports simultaneously and handles relay/dedup logic.
 */
class MeshManager(context: Context) {

    private val seenMessageIds = Collections.synchronizedSet(
        object : LinkedHashSet<String>() {
            override fun add(element: String): Boolean {
                if (size >= 500) remove(iterator().next()) // cap memory use
                return super.add(element)
            }
        }
    )

    private val bleTransport = BleMeshTransport(context)
    private val wifiTransport = WifiDirectMeshTransport(context)

    private var listener: ((MeshMessage) -> Unit)? = null

    fun start(enableWifiDirect: Boolean = false) {
        bleTransport.start { handleIncoming(it, bleTransport) }
        if (enableWifiDirect) {
            wifiTransport.start { handleIncoming(it, wifiTransport) }
        }
    }

    fun stop() {
        bleTransport.stop()
        wifiTransport.stop()
    }

    fun setWifiDirectEnabled(enabled: Boolean) {
        if (enabled && !wifiTransport.isActive) {
            wifiTransport.start { handleIncoming(it, wifiTransport) }
        } else if (!enabled && wifiTransport.isActive) {
            wifiTransport.stop()
        }
    }

    fun onMessageReceived(callback: (MeshMessage) -> Unit) {
        listener = callback
    }

    fun sendMessage(message: MeshMessage) {
        seenMessageIds.add(message.id)
        bleTransport.send(message)
        if (wifiTransport.isActive) wifiTransport.send(message)
    }

    fun setDebugListener(listener: (String) -> Unit) {
        bleTransport.setDebugListener(listener)
        wifiTransport.setDebugListener(listener)
    }

    fun setPeerConnectionListener(listener: (address: String, connected: Boolean) -> Unit) {
        bleTransport.setPeerConnectionListener(listener)
        wifiTransport.setPeerConnectionListener(listener)
    }

    private fun handleIncoming(message: MeshMessage, sourceTransport: MeshTransport) {
        if (seenMessageIds.contains(message.id)) return // dedup
        seenMessageIds.add(message.id)

        listener?.invoke(message) // deliver to UI/app layer

        if (message.ttl > 0) {
            val relayed = message.withDecrementedTtl()
            bleTransport.send(relayed)
            if (wifiTransport.isActive) wifiTransport.send(relayed)
        }
    }
}
