package com.yourapp.meshchat

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yourapp.mesh.MeshManager
import com.yourapp.mesh.MeshMessage
import com.yourapp.mesh.MessageType
import java.nio.charset.StandardCharsets
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var meshManager: MeshManager
    private lateinit var statusText: TextView
    private lateinit var messageInput: EditText
    private lateinit var messageList: RecyclerView
    private lateinit var adapter: MessageAdapter

    private val myId = UUID.randomUUID().toString().take(6)
    private val connectedPeers = mutableSetOf<String>()

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filterValues { !it }.keys
        if (denied.isEmpty()) {
            startMesh()
        } else {
            statusText.text = "Missing permissions:\n${denied.joinToString("\n")}"
            Toast.makeText(this, "Some permissions were denied", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        messageInput = findViewById(R.id.messageInput)
        messageList = findViewById(R.id.messageList)
        val sendButton: Button = findViewById(R.id.sendButton)

        adapter = MessageAdapter(mutableListOf())
        messageList.layoutManager = LinearLayoutManager(this)
        messageList.adapter = adapter

        statusText.text = "Requesting permissions…"

        meshManager = MeshManager(applicationContext)
        meshManager.onMessageReceived { message: MeshMessage ->
            runOnUiThread {
                val text = String(message.payload, StandardCharsets.UTF_8)
                adapter.addMessage(ChatMessage(message.senderId, text, isMine = false))
                messageList.scrollToPosition(adapter.itemCount - 1)
            }
        }

        meshManager.setPeerConnectionListener { address, connected ->
            runOnUiThread {
                if (connected) {
                    connectedPeers.add(address)
                    Toast.makeText(this, "Peer connected", Toast.LENGTH_SHORT).show()
                } else {
                    connectedPeers.remove(address)
                    Toast.makeText(this, "Peer disconnected", Toast.LENGTH_SHORT).show()
                }
                updateStatusText()
            }
        }

        sendButton.setOnClickListener {
            val text = messageInput.text.toString().trim()
            if (text.isNotEmpty()) {
                val message = MeshMessage(
                    senderId = myId,
                    type = MessageType.TEXT,
                    payload = text.toByteArray(StandardCharsets.UTF_8)
                )
                meshManager.sendMessage(message)
                adapter.addMessage(ChatMessage(myId, text, isMine = true))
                messageList.scrollToPosition(adapter.itemCount - 1)
                messageInput.text.clear()
            }
        }

        requestPermissions.launch(requiredPermissions())
    }

    private fun startMesh() {
        updateStatusText()
        meshManager.start(enableWifiDirect = false)
    }

    private fun updateStatusText() {
        statusText.text = if (connectedPeers.isEmpty()) {
            "Mesh active — ID: $myId — scanning for peers…"
        } else {
            "Mesh active — ID: $myId — ${connectedPeers.size} peer(s) connected"
        }
    }

    private fun requiredPermissions(): Array<String> {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        return perms.toTypedArray()
    }

    override fun onDestroy() {
        super.onDestroy()
        meshManager.stop()
    }
}
