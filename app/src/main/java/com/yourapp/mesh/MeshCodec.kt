package com.yourapp.mesh

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

/**
 * Compact binary encode/decode for MeshMessage.
 * Kept simple (no external protobuf dependency) so it's easy to build
 * entirely from Acode/GitHub Actions with no extra tooling.
 */
object MeshCodec {

    fun encode(message: MeshMessage): ByteArray {
        val bos = ByteArrayOutputStream()
        val out = DataOutputStream(bos)

        writeString(out, message.id)
        writeString(out, message.senderId)
        out.writeInt(message.ttl)
        out.writeLong(message.timestamp)
        out.writeInt(message.type.ordinal)
        writeString(out, message.channel)
        out.writeInt(message.payload.size)
        out.write(message.payload)

        return bos.toByteArray()
    }

    fun decode(bytes: ByteArray): MeshMessage? {
        return try {
            val input = DataInputStream(bytes.inputStream())

            val id = readString(input)
            val senderId = readString(input)
            val ttl = input.readInt()
            val timestamp = input.readLong()
            val typeOrdinal = input.readInt()
            val channel = readString(input)
            val payloadSize = input.readInt()
            val payload = ByteArray(payloadSize)
            input.readFully(payload)

            MeshMessage(
                id = id,
                senderId = senderId,
                ttl = ttl,
                timestamp = timestamp,
                type = MessageType.entries.getOrElse(typeOrdinal) { MessageType.TEXT },
                payload = payload,
                channel = channel
            )
        } catch (e: Exception) {
            null // malformed/truncated packet — drop it
        }
    }

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val size = input.readInt()
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }
}
