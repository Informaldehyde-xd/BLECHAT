package com.yourapp.meshchat

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide chat state shared by MeshService (background) and MainActivity (UI).
 * Messages are saved to disk so history survives the app being closed.
 */
object ChatRepository {
    private const val MAX_HISTORY = 300
    private const val PEER_FRESH_MS = 120_000L

    private val main = Handler(Looper.getMainLooper())
    private var prefs: android.content.SharedPreferences? = null

    private val messages = ArrayList<ChatMessage>()
    private val pending = ArrayList<ChatMessage>() // shown in the notification while the app is hidden
    private val debugLines = ArrayDeque<String>()
    private val connected = ConcurrentHashMap.newKeySet<String>()
    private val peerNames = ConcurrentHashMap<String, Pair<String, Long>>() // senderId -> (name, lastSeen)

    @Volatile var uiVisible = false
    @Volatile var onMessage: ((ChatMessage) -> Unit)? = null
    @Volatile var onStatus: (() -> Unit)? = null

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val sp = context.applicationContext.getSharedPreferences("meshchat_history", Context.MODE_PRIVATE)
        prefs = sp
        try {
            val arr = JSONArray(sp.getString("history", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                messages.add(
                    ChatMessage(o.getString("s"), o.getString("t"), o.getBoolean("m"), o.optLong("ts"))
                )
            }
        } catch (_: Exception) {
            // corrupt history — start fresh
        }
    }

    @Synchronized fun snapshot(): List<ChatMessage> = messages.toList()

    @Synchronized
    fun add(m: ChatMessage) {
        messages.add(m)
        while (messages.size > MAX_HISTORY) messages.removeAt(0)
        if (!uiVisible) pending.add(m)
        persist()
        main.post { onMessage?.invoke(m) }
    }

    /** Deletes the whole history on this phone (other phones keep their own copy). */
    @Synchronized
    fun clearAll() {
        messages.clear()
        pending.clear()
        persist()
    }

    /** Deletes a single message from this phone's history. */
    @Synchronized
    fun remove(m: ChatMessage) {
        messages.remove(m)
        pending.remove(m)
        persist()
    }

    @Synchronized fun pendingSnapshot(): List<ChatMessage> = pending.toList()
    @Synchronized fun clearPending() = pending.clear()

    private fun persist() {
        val arr = JSONArray()
        messages.forEach {
            arr.put(JSONObject().put("s", it.sender).put("t", it.text).put("m", it.isMine).put("ts", it.time))
        }
        prefs?.edit()?.putString("history", arr.toString())?.apply()
    }

    // ----- status -----

    fun setPeerConnected(address: String, isConnected: Boolean) {
        if (isConnected) connected.add(address) else connected.remove(address)
        notifyStatus()
    }

    fun clearPeers() {
        connected.clear()
        notifyStatus()
    }

    fun peerCount(): Int = connected.size

    fun seenPeer(senderId: String, name: String) {
        peerNames[senderId] = name to System.currentTimeMillis()
        notifyStatus()
    }

    /** Names of peers that announced themselves recently. */
    fun nearbyNames(): List<String> {
        val now = System.currentTimeMillis()
        return peerNames.values.filter { now - it.second < PEER_FRESH_MS }.map { it.first }.sorted()
    }

    @Synchronized
    fun addDebug(line: String) {
        debugLines.addLast(line)
        while (debugLines.size > 5) debugLines.removeFirst()
        notifyStatus()
    }

    @Synchronized fun debugSnapshot(): List<String> = debugLines.toList()

    private fun notifyStatus() {
        main.post { onStatus?.invoke() }
    }
}
