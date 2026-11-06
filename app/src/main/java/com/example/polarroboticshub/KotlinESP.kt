package com.example.polarroboticshub

class KotlinESP {

    // Checksum used by the ROM protocol for some commands
    //  Starts at 0xEF, add a XOR each payload byte
    private fun checksumXOREf(payload: ByteArray): Int {
        var checksum = 0xEF
        for (byte in payload) checksum = checksum xor (byte.toInt() and 0xFF)
        return checksum and 0xFF
    }


    // idk what this john does
    private fun readLe32(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    //  READ_REG (0x0A) - the payload is a 32-bit address; response puts the read value in bytes [4..7]
    internal fun decodeRegisterResponse(response: ByteArray?): Int? {
        if (response == null || response.size < 8) return null
        return readLe32(response, 4)
    }

    // Send a bootloader command and wait for the matching response packet
    // NOTE: Caller should have the chip in "download" mode
    // Based on `sendCommand`
    internal fun generateCommand(command: Int, payload: ByteArray, timeoutMs: Long = 3000): ByteArray {
        val size = payload.size
        val checksum = checksumXOREf(payload)

        val header = byteArrayOf(
            0x00, // direction (request)
            (command and 0xFF).toByte(),
            (size and 0xFF).toByte(),
            ((size ushr 8) and 0xFF).toByte(),
            (checksum and 0xFF).toByte(),
            0x00, 0x00, 0x00 // Checksum as LE32 (only low byte used for XOR checksum since im a dumb dumn)
        )

        val packet = encodeSlip(header + payload)
        return packet;
    }


    internal fun encodeSlip(payload: ByteArray): ByteArray {
        // Accounting for the command packet using the first 2 bytes of the payload for packet information
        val output = ArrayList<Byte>(payload.size + 2)

        // Every SLIP packet starts and ends with '0xC0'
        output.add(0xC0.toByte())

        // All occurrences of the byte '0xC0' are replaced with '0xDB 0xDC'
        // All occurrences of the byte '0xDB' are replaced with '0xDB 0xDD'
        //  This splits the raw byte stream into readable frames
        for (byte in payload) {
            when (byte) {
                0xC0.toByte() -> {
                    output.add(0xDB.toByte())
                    output.add(0xDC.toByte())
                }
                0xDB.toByte() -> {
                    output.add(0xDB.toByte())
                    output.add(0xDD.toByte())
                }
                else -> output.add(byte)
            }
        }

        // '0xC0' sandwich
        output.add(0xC0.toByte())
        return output.toByteArray()
    }

    internal fun decodeMac(word1: Int, word2: Int): String {
        //     EFUSE_BLK0_RDATA1_REG
        //     EFUSE_BLK0_RDATA2_REG

        // DEBUG
        // SerialSession.onStatus?.invoke("EFUSE w1=%08X w2=%08X".format(w1, w2))

        val byte6 = (word2 ushr 8) and 0xFF
        val byte7 = word2 and 0xFF
        val byte8 = (word1 ushr 0) and 0xFF
        val byte9 = (word1 ushr 8) and 0xFF
        val byte10 = (word1 ushr 16) and 0xFF
        val byte11 = (word1 ushr 24) and 0xFF

        return "%02X:%02X:%02X:%02X:%02X:%02X".format(byte6, byte7, byte11, byte10, byte9, byte8)
    }

    internal fun buildFlashDataPacket(sequence: Int, data: ByteArray): ByteArray {

        return TODO("Provide the return value")
    }


}