package com.example.polarroboticshub

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlipDecoderTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun preservesTwoPacketsFromOneRead() {
        val decoder = SlipDecoder()
        val input = bytes(0xC0, 0x01, 0x02, 0xC0, 0xC0, 0x03, 0x04, 0xC0)

        decoder.feed(input, input.size)

        assertArrayEquals(bytes(0x01, 0x02), decoder.nextPacket())
        assertArrayEquals(bytes(0x03, 0x04), decoder.nextPacket())
        assertNull(decoder.nextPacket())
    }

    @Test
    fun preservesEscapeAcrossReads() {
        val decoder = SlipDecoder()
        val first = bytes(0xC0, 0x01, 0xDB)
        val second = bytes(0xDC, 0x02, 0xC0)

        decoder.feed(first, first.size)
        assertNull(decoder.nextPacket())

        decoder.feed(second, second.size)
        assertArrayEquals(bytes(0x01, 0xC0, 0x02), decoder.nextPacket())
    }

    @Test
    fun checksRomStatusAndLength() {
        val esp = KotlinESP()
        val reply = byteArrayOf(
            1, 0x0A, 4, 0,   // Response, READ_REG, 4 data bytes
            0, 0, 0, 0,      // Register value
            0, 0, 0, 0       // Success, no error, reserved, reserved
        )

        org.junit.Assert.assertEquals(KotlinESP.Reply.OK, esp.checkROMReply(reply, 0x0A))
        org.junit.Assert.assertEquals(KotlinESP.Reply.OTHER_COMMAND, esp.checkROMReply(reply, 0x08))

        reply[8] = 1
        reply[9] = 5
        org.junit.Assert.assertEquals(KotlinESP.Reply.DEVICE_ERROR, esp.checkROMReply(reply, 0x0A))

        reply[2] = 7 // Claims more bytes than actually arrived
        org.junit.Assert.assertEquals(KotlinESP.Reply.MALFORMED, esp.checkROMReply(reply, 0x0A))
    }
}