package com.jayanth.droidbridge.network

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Locale

data class MacHello(
    val deviceId: String,
    val name: String,
    val model: String,
    val platform: String,
    val fingerprint: String,
    val paired: Boolean,
    val battery: Int,
    val osVersion: String,
)

data class ControlMessage(
    val action: String,
    val value: String?,
)

data class WireFrame(
    val type: Int,
    val payload: ByteArray,
)

object WireProtocol {
    const val TYPE_HELLO = 0x01
    const val TYPE_VIDEO = 0x02
    const val TYPE_AUDIO = 0x03
    const val TYPE_CLIPBOARD = 0x04
    const val TYPE_CONTROL = 0x05
    const val TYPE_PING = 0x06

    private const val MAX_FRAME = 16 * 1024 * 1024

    fun hello(
        deviceId: String,
        name: String,
        model: String,
        fingerprint: String,
        paired: Boolean,
        battery: Int,
        osVersion: String,
    ): ByteArray {
        val json = JSONObject()
            .put("kind", "hello")
            .put("protocol", 2)
            .put("deviceId", deviceId)
            .put("name", name)
            .put("model", model)
            .put("platform", "android")
            .put("fingerprint", fingerprint)
            .put("paired", paired)
            .put("battery", battery)
            .put("osVersion", osVersion)
        return frame(TYPE_HELLO, json.toString().toByteArray(Charsets.UTF_8))
    }

    fun control(action: String, value: String? = null): ByteArray {
        val json = JSONObject()
            .put("kind", "control")
            .put("action", action)
            .put("value", value ?: JSONObject.NULL)
        return frame(TYPE_CONTROL, json.toString().toByteArray(Charsets.UTF_8))
    }

    fun ping(payload: ByteArray = ByteArray(0)): ByteArray = frame(TYPE_PING, payload)

    fun video(
        width: Int,
        height: Int,
        rotation: Int,
        keyframe: Boolean,
        presentationTimeUs: Long,
        accessUnit: ByteArray,
    ): ByteArray {
        val metadata = JSONObject()
            .put("codec", "avc")
            .put("w", width)
            .put("h", height)
            .put("rotation", rotation)
            .put("key", keyframe)
            .put("pts", presentationTimeUs)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val payload = ByteArray(4 + metadata.size + accessUnit.size)
        payload[0] = (metadata.size ushr 24).toByte()
        payload[1] = (metadata.size ushr 16).toByte()
        payload[2] = (metadata.size ushr 8).toByte()
        payload[3] = metadata.size.toByte()
        metadata.copyInto(payload, 4)
        accessUnit.copyInto(payload, 4 + metadata.size)
        return frame(TYPE_VIDEO, payload)
    }

    fun write(output: DataOutputStream, data: ByteArray) {
        synchronized(output) {
            output.write(data)
            output.flush()
        }
    }

    fun read(input: DataInputStream): WireFrame {
        val length = input.readInt()
        require(length in 1..MAX_FRAME) { "Invalid frame length $length" }
        val type = input.readUnsignedByte()
        val payload = ByteArray(length - 1)
        input.readFully(payload)
        return WireFrame(type, payload)
    }

    fun parseHello(payload: ByteArray): MacHello {
        val json = JSONObject(payload.toString(Charsets.UTF_8))
        return MacHello(
            deviceId = json.optString("deviceId", json.optString("deviceID")),
            name = json.optString("name", "Mac"),
            model = json.optString("model", "Mac"),
            platform = json.optString("platform", "macos"),
            fingerprint = json.optString("fingerprint"),
            paired = json.optBoolean("paired", false),
            battery = json.optInt("battery", -1),
            osVersion = json.optString("osVersion", "macOS"),
        )
    }

    fun parseControl(payload: ByteArray): ControlMessage {
        val json = JSONObject(payload.toString(Charsets.UTF_8))
        return ControlMessage(
            action = json.optString("action"),
            value = json.optString("value").takeIf { it.isNotBlank() },
        )
    }

    fun fingerprint(certificate: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate)
        return digest.joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
    }

    fun pairingCode(local: String, peer: String): String {
        val ordered = listOf(local, peer).sorted().joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("droidbridge-sac-v2|$ordered".toByteArray(Charsets.UTF_8))
        var value = 0L
        for (index in 0 until 4) {
            value = (value shl 8) or (digest[index].toLong() and 0xff)
        }
        return "%06d".format(Locale.US, value % 1_000_000)
    }

    private fun frame(type: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 1
        val data = ByteArray(4 + length)
        data[0] = (length ushr 24).toByte()
        data[1] = (length ushr 16).toByte()
        data[2] = (length ushr 8).toByte()
        data[3] = length.toByte()
        data[4] = type.toByte()
        payload.copyInto(data, 5)
        return data
    }
}
