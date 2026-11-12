package com.example.polarroboticshub

// program from hell
class KotlinESP {

    // Helpers to simplify ESP responses
    internal enum class Reply {
        OK, OTHER_COMMAND, MALFORMED, DEVICE_ERROR
    }

    // Checksum used by the ROM protocol for some commands
    //  Starts at 0xEF, add a XOR each payload byte
    private fun checksumXOREf(payload: ByteArray): Int {
        var checksum = 0xEF
        for (byte in payload) checksum = checksum xor (byte.toInt() and 0xFF)
        return checksum and 0xFF
    }


    // Handy array conversion
    private fun le32(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte()
    )

    // idk what this john does
    // something with reading a Little-Endian 32-byte guy
    private fun readLe32(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    // Typical ESP-32 ROM ends a reply with 4 bytes:
    //      status, error code, and two reserved bytes
    internal fun checkROMReply(packet: ByteArray, expectedCommand: Int): Reply {
        // If packet isn't 8 bytes or if the status isn't `1` then its malformed
        if (packet.size < 8 || packet[0].toInt() != 1) return Reply.MALFORMED

        val command = packet[1].toInt() and 0xFF
        if (command != expectedCommand) return Reply.OTHER_COMMAND

        val dataSize = (packet[2].toInt() and 0xFF) or ((packet[3].toInt() and 0xFF) shl 8)
        if (dataSize < 4 || packet.size != 8 + dataSize) return Reply.MALFORMED

        //
        val status = packet[packet.size - 4].toInt() and 0xFF
        return if (status == 0) Reply.OK else Reply.DEVICE_ERROR
    }

    //  READ_REG (0x0A) - the payload is a 32-bit address; response puts the read value in bytes [4..7]
    internal fun decodeRegisterResponse(response: ByteArray?): Int? {
        if (response == null || response.size < 8) return null
        return readLe32(response, 4)
    }

    // Send a bootloader command and wait for the matching response packet
    // NOTE: Caller should have the chip in "download" mode
    // Based on `sendCommand`
    internal fun generateCommand(command: Int, payload: ByteArray): ByteArray {
        require(command in 0..0xFF)
        require(payload.size <= 0xFFFF)

        val checksum = when (command) {
            0x03, 0x07, 0x11 -> {
                // FLASH_DATA, MEM_DATA, FLASH_DEFL_DATA
                require(payload.size >= 16) {"Data command requires a 16-byte metadata header"}
                require(readLe32(payload, 0) == payload.size - 16) {"Declared data length does not match payload"}
                checksumXOREf(payload.copyOfRange(16, payload.size))
            }
            else -> 0
        }

        val header = byteArrayOf(
            0x00,
            command.toByte(),
            (payload.size and 0xFF).toByte(),
            ((payload.size ushr 8) and 0xFF).toByte()
        ) + le32(checksum)

        return encodeSlip(header + payload)
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


    // SPI_ATTACH Implementation (0x0D)
//    internal fun SPIAttachFromROMDefault(): Boolean {
//        // 0 = default SPI flash pins and e/Fuse configuration
//        //  ROM loader requires 4 exta bytes
//        val payload = readLe32(, 25) + byteArrayOf(0, 0, 0, 0)
//        val response = sendCommand(0x0D, payload, timeoutMs = 3000) ?: return false
//
//        return TODO("Provide the return value")
//    }

    internal fun buildFlashDataPacket(sequence: Int, data: ByteArray): ByteArray {
        require(sequence >= 0) {"Sequence must be non-negative"}
        require(data.isNotEmpty() && data.size <= 1024) {"Flash blocks must be between 1-1024 bytes"}

        return le32(data.size) + le32(sequence) + le32(0) + le32(0) + data
    }

    internal fun buildFlashBeginPayload(imageSize: Int, offset: Int): ByteArray {
        require(imageSize in 1..(2 * 1024 * 1024))
        require(offset >= 0 && offset % 4096 == 0 ) {"Flash offset must be a factor of a 4096-byte sector"}

        val blockSize = 1024
        val blockCount = (imageSize + blockSize - 1) / blockSize
        val eraseSize = ((imageSize + 4095) / 4096) * 4096

        return le32(eraseSize) + le32(blockCount) + le32(blockSize) + le32(offset)
    }

}