package com.example.polarroboticshub

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class FlashPacketTest {
    private val esp = KotlinESP()

    private fun bytes(vararg values: Int) =
        ByteArray(values.size) { values[it].toByte() }

    @Test
    fun flashPayloadContainsLengthSequenceAndData() {
        val payload = esp.buildFlashDataPacket(
            sequence = 0x0102,
            data = bytes(0x12, 0x34, 0x56)
        )

        assertArrayEquals(
            bytes(
                3, 0, 0, 0,       // Three firmware bytes
                2, 1, 0, 0,       // Sequence 0x0102, little endian
                0, 0, 0, 0,
                0, 0, 0, 0,
                0x12, 0x34, 0x56
            ),
            payload
        )
    }

    @Test
    fun flashCommandChecksumsOnlyDataAndEscapesIt() {
        val payload = esp.buildFlashDataPacket(
            sequence = 1,
            data = bytes(0xC0, 0xDB, 0x01)
        )

        // 0xEF XOR 0xC0 XOR 0xDB XOR 0x01 = 0xF5
        assertArrayEquals(
            bytes(
                0xC0,             // SLIP start
                0x00, 0x03, 19, 0,          // FLASH_DATA, payload length
                0xF5, 0, 0, 0,              // Data-only checksum
                3, 0, 0, 0,                 // Data length
                1, 0, 0, 0,                 // Sequence
                0, 0, 0, 0,
                0, 0, 0, 0,
                0xDB, 0xDC,                 // Escaped 0xC0
                0xDB, 0xDD,                 // Escaped 0xDB
                0x01,
                0xC0                        // SLIP end
            ),
            esp.generateCommand(0x03, payload)
        )
    }

    @Test
    fun readRegisterCommandUsesZeroChecksum() {
        assertArrayEquals(
            bytes(
                0xC0,
                0, 0x0A, 4, 0,
                0, 0, 0, 0,
                4, 0xA0, 0xF5, 0x3F,
                0xC0
            ),
            esp.generateCommand(
                0x0A,
                bytes(4, 0xA0, 0xF5, 0x3F)
            )
        )
    }

    @Test
    fun flashBeginRoundsEraseAndBlockCountsUp() {
        assertArrayEquals(
            bytes(
                0x00, 0x20, 0, 0,  // Erase 8192 bytes
                5, 0, 0, 0,        // Five 1024-byte blocks
                0, 4, 0, 0,        // Block size 1024
                0, 0, 1, 0         // Offset 0x10000
            ),
            esp.buildFlashBeginPayload(
                imageSize = 4097,
                offset = 0x10000
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun flashBeginRejectsUnalignedOffset() {
        esp.buildFlashBeginPayload(
            imageSize = 1024,
            offset = 0x10001
        )
    }
}