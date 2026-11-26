package com.droidbridge.protocol.session

/**
 * Payload of the `device.info` control message (NETWORK_PROTOCOL §7).
 *
 * The Mac's sidebar said "Android device" because the handshake carries no model name and the app
 * refuses to invent one. This message is that sentence: the same facts the phone's own About screen
 * shows, and nothing that identifies the person holding it - no serial number, no build fingerprint,
 * no user-set device name, no account, no advertising identifier. The peer already proved it holds a
 * pin this Mac trusts, so the model name is not new information to it; a name a stranger could learn
 * from an unauthenticated packet would be.
 *
 * Sent by the phone each time a session reaches CONNECTED, including silent reconnects: a Mac that
 * restarted has lost its copy and must be told again.
 */
class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
    val appVersion: String,
) {

    init {
        require(manufacturer.isNotBlank()) { "manufacturer must not be blank" }
        require(model.isNotBlank()) { "model must not be blank" }
        require(androidRelease.isNotBlank()) { "androidRelease must not be blank" }
        require(sdkInt > 0) { "sdkInt must be positive, was $sdkInt" }
        require(appVersion.isNotBlank()) { "appVersion must not be blank" }
        listOf(manufacturer, model, androidRelease, appVersion).forEach { value ->
            require(value.length <= MAX_FIELD_CHARS) {
                "device info field exceeds $MAX_FIELD_CHARS characters"
            }
        }
    }

    /** The line the Mac shows for this phone, e.g. `samsung SM-S928B`. */
    val displayName: String get() = "$manufacturer $model"

    /**
     * Serialises the payload for a `device.info` control message.
     *
     * Key order is fixed so the encoding is deterministic and can be asserted as a test vector, and
     * every value goes through [quote] because a model string is device-controlled text, not a literal.
     */
    fun toJsonPayload(): String = buildString {
        append('{')
        append("\"manufacturer\":").append(quote(manufacturer)).append(',')
        append("\"model\":").append(quote(model)).append(',')
        append("\"androidRelease\":").append(quote(androidRelease)).append(',')
        append("\"sdkInt\":").append(sdkInt).append(',')
        append("\"appVersion\":").append(quote(appVersion))
        append('}')
    }

    override fun toString(): String = "DeviceInfo($displayName, Android $androidRelease, app $appVersion)"

    companion object {
        /** Bounded so a peer cannot fill the control channel with one message. */
        const val MAX_FIELD_CHARS: Int = 64

        private fun quote(value: String): String = buildString {
            append('"')
            value.forEach { c ->
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
                }
            }
            append('"')
        }
    }
}