package com.example.polarroboticshub

internal class SlipDecoder {
    private var inFrame = false
    private var frame = ArrayList<Byte>()
    private val completedPackets = ArrayDeque<ByteArray>()

    // Function that parses received bytes
    internal fun feed(bytes: ByteArray, count: Int) {
        require(count in 0..bytes.size)

        for (i in 0 until count) {
            val byte = bytes[i]
            // Ignoring any packets before 0xC0 (garbage)

            // Seeing if the packet is complete before adding it to the list
            if (byte == 0xC0.toByte()) {
                if (inFrame && frame.isNotEmpty()) {
                    val packet = slipUnescape(frame.toByteArray())
                    if (packet != null) { completedPackets.addLast(packet) }
                }
                // Only wiping once all parts of a packet have been designated
                frame.clear()
                inFrame = true
            } else if (inFrame) {
                frame.add(byte)

                // ESP command packets can have up to 8-byte headers & 65535 payload bytes; escaping can double it
                if (frame.size > 2 * (8 + 65535)) {
                    frame.clear()
                    inFrame = false
                }
            }
        }


    }

    internal fun nextPacket(): ByteArray? {
        return if (completedPackets.isEmpty()) { null }
        else { completedPackets.removeFirst() }
    }

    internal fun reset() {
        inFrame = false
        frame.clear()
        completedPackets.clear()
    }




    // Parsing a SLIP frame sent from the ESP
    internal fun slipUnescape(data: ByteArray): ByteArray? {
        // No need to account for header data as frame is already split
        val output = ArrayList<Byte>(data.size)
        var i = 0

        while (i < data.size) {
            val currentByte = data[i++]

            if (currentByte != 0xDB.toByte()) {
                output.add(currentByte)
                continue
            }

            // An escape byte must have another byte after it
            if (i >= data.size) return null

            // Reverting the SLIP information back into its original form
            // by replacing all Transposed Frame information with regular Frame information
            when (data[i++]) {
                0xDC.toByte() -> output.add(0xC0.toByte())
                0xDD.toByte() -> output.add(0xDB.toByte())
                else -> return null
            }
        }

        return output.toByteArray()
    }
}