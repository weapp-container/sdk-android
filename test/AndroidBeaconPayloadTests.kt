package com.sonofmagic.weapp.container.sdk

import java.util.UUID

/** Protocol boundary tests on authored bytes; no radio or physical-beacon acceptance. */
fun main() {
    val hex = "0215e2c56db5dffb48d2b060d0f5a71096e0ffff0001c5"
    val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    val beacon = checkNotNull(AndroidBeaconPayload.decode(bytes, -59))
    check(beacon.uuid == UUID.fromString("e2c56db5-dffb-48d2-b060-d0f5a71096e0"))
    check(beacon.major == 65535 && beacon.minor == 1 && beacon.rssi == -59 && beacon.accuracy == 1.0)
    check(AndroidBeaconPayload.decode(bytes.copyOf(22), -59) == null)
    check(AndroidBeaconPayload.decode(bytes + byteArrayOf(0), -59) == null)
    check(AndroidBeaconPayload.decode(bytes.clone().apply { this[1] = 0x16 }, -59) == null)
    check(AndroidBeaconPayload.decode(bytes, -128) == null)
    check(AndroidBeaconPayload.decode(bytes, 0)?.accuracy == -1.0)
    check(AndroidBeaconPayload.decode(bytes.clone().apply { this[22] = 0 }, -59)?.accuracy == -1.0)
    println("iBeacon payload UUID, unsigned IDs, distance and malformed-boundary checks passed")
}
