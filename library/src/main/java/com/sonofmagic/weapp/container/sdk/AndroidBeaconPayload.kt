package com.sonofmagic.weapp.container.sdk

import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.pow

/** Apple manufacturer data after its 0x004c company identifier. No peripheral identifiers escape. */
internal data class AndroidBeaconPayload(val uuid: UUID, val major: Int, val minor: Int, val rssi: Int, val accuracy: Double) {
    val key: String get() = "$uuid/$major/$minor"
    companion object {
        fun decode(bytes: ByteArray, rssi: Int): AndroidBeaconPayload? {
            if (bytes.size != 23 || bytes[0] != 2.toByte() || bytes[1] != 0x15.toByte() || rssi !in -127..20) return null
            val buffer = ByteBuffer.wrap(bytes, 2, 16)
            val uuid = UUID(buffer.long, buffer.long)
            val power = bytes[22].toInt()
            // Android has no native proximity class. Distance is an RSSI path-loss estimate.
            val accuracy = if (power >= 0 || rssi == 0) -1.0 else 10.0.pow((power - rssi) / 20.0)
            return AndroidBeaconPayload(uuid, ((bytes[18].toInt() and 255) shl 8) or (bytes[19].toInt() and 255), ((bytes[20].toInt() and 255) shl 8) or (bytes[21].toInt() and 255), rssi, accuracy)
        }
    }
}
