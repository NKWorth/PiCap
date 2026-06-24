package com.picap.mobile.ble

import java.util.UUID

object PicapUuids {
    const val DEVICE_NAME = "PiCap"

    val SERVICE: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
    val CONFIG: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567891")
    val CAPTURE: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567892")
    val LATEST: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567893")
    val HISTORY: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567894")
    val STATUS: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567895")

    val CLIENT_CONFIG_DESCRIPTOR: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
