package com.yourapp.mesh

import java.util.UUID

/**
 * Core message envelope shared across all transports (BLE, Wi-Fi Direct).
 * Kept small and serializable for BLE's tight packet size limits.
 */
data class MeshMessage(
    val id: String = UUID.randomUUID().toString(),
    val senderId: String,
    var ttl: Int = 6,
    val timestamp: Long = System.currentTimeMillis(),
    val type: MessageType = MessageType.TEXT,
    val payload: ByteArray,
    val channel: String = "public"
) {
    fun withDecrementedTtl(): MeshMessage = this.copy(ttl = ttl - 1)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MeshMessage) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

enum class MessageType {
    TEXT, ACK, IMAGE_CHUNK, PRESENCE
}
