package com.example.polarroboticshub

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.os.Build
import com.example.polarroboticshub.ui.theme.PolarRoboticsHubTheme
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


private const val ACTION_USB_PERMISSION = "com.example.polarroboticshub.USB_PERMISSION"

private object SerialSession {
    var connection: android.hardware.usb.UsbDeviceConnection? = null
    var port: UsbSerialPort? = null
    var device: UsbDevice? = null

    private var executor: ExecutorService? = null
    private var mainHandler = Handler(Looper.getMainLooper())

    @Volatile var onText: ((String) -> Unit)? = null
    @Volatile var onStatus: ((String) -> Unit)? = null

    fun startIO() {
        val port = port ?: return
        stopIO()

        executor = Executors.newSingleThreadExecutor().also { exec ->
            exec.execute {
                val buffer = ByteArray(1024)
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        val n = port.read(buffer, 200)
                        if (n > 0) {
                            val text = try { String(buffer, 0, n, Charsets.UTF_8) } catch (_: Exception) { "" }
                            val textCleaned = text.filter { ch ->
                                ch == '\n' || ch == '\r' || ch == '\t' || (ch.code in 32..126)
                            }
                            if (textCleaned.isNotEmpty()) {
                                mainHandler.post { onText?.invoke(textCleaned) }
                            }
                        }
                    } catch (e: Exception) {
                        mainHandler.post {
                            onStatus?.invoke("Serial read error (unplug?)")
                            closeConnection()
                        }
                        break
                    }
                }
            }
        }

        mainHandler.post { onStatus?.invoke("IO Reader started") }
    }

    fun stopIO() {
        try { executor?.shutdownNow() } catch (_: Exception) {}
        executor = null
    }

    fun closeConnection() {
        stopIO()
        try {
            port?.close()
            port = null
        } catch (_: Exception) {}

        try {
            connection?.close()
            connection = null
        } catch (_: Exception) {}

        device = null
        mainHandler.post { onStatus?.invoke("Closed!") }
    }
}

private fun findCh340Device(manager: UsbManager): UsbDevice? {
    return manager.deviceList.values.firstOrNull { device ->
        val vendorId = device.vendorId
        val productId = device.productId
        vendorId == 0x1A86 && productId == 0x7523
    }
}

fun openCh340OrRequestPermission(context: Context) {
    val manager = context.getSystemService(UsbManager::class.java)!!
    val device = findCh340Device(manager)

    if (device == null) {
        Toast.makeText(context, "No CH340 Device Found", Toast.LENGTH_SHORT).show()
        return
    }

    if (!manager.hasPermission(device)) {
        val permissionIntent = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE
        )
        manager.requestPermission(device, permissionIntent)
        Toast.makeText(context, "Requesting USB Permission", Toast.LENGTH_SHORT).show()
        return
    }

    openDeviceNow(context, device)
}

class MainActivity : ComponentActivity() {
    private val usbReciever = object: BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val ctx = context ?: return
            val it = intent ?: return
            val manager = ctx.getSystemService(UsbManager::class.java) ?: return
            val device: UsbDevice? = it.getParcelableExtra(UsbManager.EXTRA_DEVICE)

            when (it.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = it.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) {
                        openDeviceNow(ctx, device)
                    } else {
                        Toast.makeText(ctx, "USB Permission Denied", Toast.LENGTH_SHORT).show()
                    }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (device != null && SerialSession.device?.deviceName == device.deviceName) {
                        SerialSession.closeConnection()
                        Toast.makeText(ctx, "USB Device Detached", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReciever, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbReciever, filter)
        }

        setContent {
            PolarRoboticsHubTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PolarRoboticsHub(Modifier.padding(innerPadding))
                }
            }
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(usbReciever)
        } catch (_: Exception) {}
        SerialSession.closeConnection()
        super.onDestroy()
    }
}


/* FUNCTIONS */
fun describeDevice(device: UsbDevice): String {
    fun hex(n: Int) = "0x" + n.toString(16).uppercase().padStart(4, '0')

    val sb = StringBuilder()
    sb.appendLine("Device: ${device.deviceName}")
    sb.appendLine("VID: ${hex(device.vendorId)}   PID: ${hex(device.productId)}")
    sb.appendLine("Class: ${device.deviceClass}  Sub: ${device.deviceSubclass}  Proto: ${device.deviceProtocol}")
    sb.appendLine("Interfaces: ${device.interfaceCount}")

    for (i in 0 until device.interfaceCount) {
        val intf = device.getInterface(i)
        sb.appendLine("  IF $i -> class=${intf.interfaceClass}, sub=${intf.interfaceSubclass}, proto=${intf.interfaceProtocol}, endpoints=${intf.endpointCount}")

        for (e in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(e)
            val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
            val type = when (ep.type) {
                UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
                UsbConstants.USB_ENDPOINT_XFER_INT -> "INT"
                UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOC"
                UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CTRL"
                else -> "?"
            }
            sb.appendLine("     EP $e -> $dir / $type / addr=${ep.address} / maxPacket=${ep.maxPacketSize}")
        }
    }

    return sb.toString()
}

fun detectESP32Board(device: UsbDevice): Boolean {
    val vendorID = device.vendorId

    return when (vendorID) {
        0x10C4 -> true // Silicon Labs (CP210x)
        0x1A86 -> true // WCH (CH340/CH910x)
        0x0403 -> true // FTDI
        0x303A -> true // Espressif

        else -> false
    }
}


fun getDeviceData(context: Context, deviceList: HashMap<String, UsbDevice>): String {
    if (deviceList.isEmpty()) {
        Toast.makeText(context, "No USB Devices Found", Toast.LENGTH_SHORT).show()

        return "3"
    } else {
        for (device in deviceList.values) {
            Toast.makeText(context,"Device detected", Toast.LENGTH_SHORT).show()
        }

        return "null"
    }
}

fun openDeviceForWriting(context: Context) {

    // Find all available drivers from attached devices.
    val manager = context.getSystemService(UsbManager::class.java)!!
    val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
    if (availableDrivers.isEmpty()) {
        return
    }


    // Open a connection to the first available driver.
    val driver = availableDrivers.get(0)
    var connection = manager.openDevice(driver.getDevice())
    if (connection == null) {
        val permissionIntent = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE
        )
        manager.requestPermission(driver.getDevice(), permissionIntent)
        connection = manager.openDevice(driver.getDevice())
    }

    val port = driver.getPorts().get(0) // Most devices have just one port (port 0)
    port.open(connection)
    port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

    SerialSession.connection = connection
    SerialSession.port = port
    SerialSession.device = driver.device

    Toast.makeText(context, "Serial Connection Opened", Toast.LENGTH_SHORT).show()
}

fun readFromSerial(timeoutMs: Int = 200): String? {
    val buffer = ByteArray(256)
    val length = SerialSession.port?.read(buffer, timeoutMs) ?: return null
    if (length <= 0) {
        return null
    } else {
        return String(buffer, 0, length, Charsets.UTF_8)
    }
}

fun openDeviceNow(context: Context, device: UsbDevice) {
    val manager = context.getSystemService(UsbManager::class.java)!!
    SerialSession.closeConnection()

    val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
    if (driver == null) {
        Toast.makeText(context, "No USB-Serial Driver Found", Toast.LENGTH_SHORT).show()
        return
    }

    val connection = manager.openDevice(device)
    if (connection == null) {
        Toast.makeText(context, "openDevice() returned null", Toast.LENGTH_SHORT).show()
        return
    }

    val port = driver.ports.firstOrNull()
    if (port == null) {
        connection.close()
        Toast.makeText(context, "No Serial ports Found", Toast.LENGTH_SHORT).show()
        return
    }

    try {
        port.open(connection)
        port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

        SerialSession.connection = connection
        SerialSession.port = port
        SerialSession.device = device
        SerialSession.startIO()

        Toast.makeText(context, "Serial Connection Opened", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        try { port.close() } catch (_: Exception) {}
        try { connection.close() } catch (_: Exception) {}
        SerialSession.closeConnection()
        Toast.makeText(context, "Failed to open serial: ${e.message}", Toast.LENGTH_LONG).show()
        SerialSession.onStatus?.invoke("Failed to open serial: ${e.message}")
        return
    }
}

/* ------------------------------- */
// ESP-IDF PROTOCOL IMPLEMENTATION //
/* ------------------------------- */

// This runs off of SLIP (Serial Line Internet Protocol), an encapsulation of the Internet Protocol
// for serial and serial-like communications
//
// Byte translations are:
// '0xC0' -> (END) Frame End
// 'OxDB' -> (ESC) Frame Escape
// '0xDC' -> (ESC_END) Transposed Frame End
// '0xDD' -> (ESC_ESC) Transposed Frame Escape


// Heavily commented for now so I understand what the freak is going on

// ------- Bootloader Command Helpers (from ESP32 ROM Protocol) ------- //
private fun le32(value: Int): ByteArray = byteArrayOf(
    (value and 0xFF).toByte(),
    ((value ushr 8) and 0xFF).toByte(),
    ((value ushr 16) and 0xFF).toByte(),
    ((value ushr 24) and 0xFF).toByte()
)

private fun readLe32(bytes: ByteArray, offset: Int): Int {
    return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}

// Checksum used by the ROM protocol for some commands
//  Starts at 0xEF, add a XOR each payload byte
private fun checksumXOREf(payload: ByteArray): Int {
    var checksum = 0xEF
    for (byte in payload) checksum = checksum xor (byte.toInt() and 0xFF)
    return checksum and 0xFF
}

// Send a bootloader command and wait for the matching response packet
// NOTE: Caller should have the chip in "download" mode
private fun sendCommand(command: Int, payload: ByteArray, timeoutMs: Long = 3000): ByteArray? {
    val port = SerialSession.port ?: return null
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

    val packet = slipEncode(header + payload)
    try {
        port.write(packet, 1000)
    } catch (_: Exception) {
        return null
    }

    val startTime = System.currentTimeMillis()
    while (System.currentTimeMillis() - startTime < timeoutMs) {
        val response = slipTryToReadPacket(800) ?: continue

        //  Response header: dir, cmd, size(2), value(4), data...
        if (response.size < 8) continue
        if (response[0] != 0x01.toByte()) continue
        if ((response[1].toInt() and 0xFF) != (command and 0xFF)) continue
        return response
    }
    return null
}

// SPI_ATTACH Implementation (0x0D)
private fun SPIAttachFromROMDefault(): Boolean {
    // 0 = default SPI flash pins and e/Fuse configuration
    //  ROM loader requires 4 exta bytes
    val payload = le32(0) + byteArrayOf(0, 0, 0, 0)
    val response = sendCommand(0x0D, payload, timeoutMs = 3000) ?: return false

}
//  READ_REG (0x0A) - the payload is a 32-bit address; response puts the read value in bytes [4..7]
private fun readRegister(address: Int): Int? {
    val response = sendCommand(0x0A, le32(address), timeoutMs = 3000) ?: return null

    if (response.size < 8) return null
    return readLe32(response, 4)
}

//  The ESP32's base MAC address is stored in eFuse BLK0 read-data registers:
//      - bits [31:0] are stored in EFUSE_BLK0_RDATA1_REG
//      - bits [55:32] are stores in EFUSE_BLK0_RDATA_2_REG[23:0]
private fun readMacAddressFromEfuse(): String? {
    val w1 = readRegister(0x3FF5A004) ?: return null //     EFUSE_BLK0_RDATA1_REG
    val w2 = readRegister(0x3FF5A008) ?: return null //     EFUSE_BLK0_RDATA2_REG

    // DEBUG
    // SerialSession.onStatus?.invoke("EFUSE w1=%08X w2=%08X".format(w1, w2))

    val byte6 = (w1 ushr 16) and 0xFF
    val byte7 = (w1 ushr 24) and 0xFF
    val byte8 = (w2 ushr 0) and 0xFF
    val byte9 = (w2 ushr 8) and 0xFF
    val byte10 = (w2 ushr 16) and 0xFF
    val byte11 = (w2 ushr 24) and 0xFF

    return "%02X:%02X:%02X:%02X:%02X:%02X".format(byte11, byte10, byte9, byte8, byte7, byte6)
}

// Creates a slip packet to send to the ESP
private fun slipEncode(payload: ByteArray): ByteArray {
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

// Reading SLIP packets sent from ESP
private fun slipTryToReadPacket(timeoutMsTotal: Long = 1500): ByteArray? {
    // Grabbing current serial connection
    val port = SerialSession.port ?: return null

    // Setting information to begin reading SLIP packets sent from the ESP
    val start = System.currentTimeMillis()
    val buffer = ByteArray(64)
    val frame = ArrayList<Byte>(256)
    var inFrame = false

    // Reading SLIP packets
    while (System.currentTimeMillis() - start < timeoutMsTotal) {
        // Checking if there's any packets left to read
        val n = try { port.read(buffer, 50) } catch (_: Exception) { return null }
        if (n <= 0) continue

        // Scanning for an ESC
        for (i in 0 until n) {
            val byte = buffer[i]
            if (byte == 0xC0.toByte()) {
                if (inFrame && frame.isNotEmpty()) {
                    // At the end of a frame; return it for reading
                    return slipUnescape(frame.toByteArray())
                } else {
                    // At the start of a frame; begin reading
                    frame.clear()
                    inFrame = true
                }
            } else if (inFrame) {
                frame.add(byte)
            }
        }
    }
    return null
}

// Parsing a SLIP frame sent from the ESP
private fun slipUnescape(data: ByteArray): ByteArray {
    // No need to account for header data as frame is already split
    val output = ArrayList<Byte>(data.size)
    var i = 0

    while (i < data.size) {
        val currentByte = data[i]
        if (currentByte == 0xDB.toByte() && i + 1 < data.size) {
            val n = data[i + 1]

            // Reverting the SLIP information back into its original form
            // by replacing all Transposed Frame information with regular Frame information
            when (n) {
                0xDC.toByte() -> output.add(0xC0.toByte())
                0xDD.toByte() -> output.add(0xDB.toByte())
                else -> {
                    output.add(currentByte)
                    output.add(n)
                }
            }
            i += 2
        } else {
            output.add(currentByte)
            i++
        }

        // i is two different values to jump over the header information if it is at the begining
        // of a packet
    }

    return output.toByteArray()
}

/* --------------------------------- */
// ESP-IDF BOOTLOADER IMPLEMENTATION //
/* --------------------------------- */

// Most ESP boards support auto-booting
private fun enterBootloaderAuto(port: UsbSerialPort) {
    // Derived from ESP-IDF's bootloader.c
    port.dtr = true
    port.rts = true
    Thread.sleep(50)

    port.dtr = false
    port.rts = true
    Thread.sleep(50)

    port.rts = false
    Thread.sleep(50)

    port.dtr = true
    Thread.sleep(50)
}

// Attempting to SYNC the board and the machine
suspend fun tryBootloaderSync(): Boolean = withContext(Dispatchers.IO) {
    val port = SerialSession.port ?: return@withContext false

    // Pausing background reader to prevent interference
    SerialSession.stopIO()
    var success = false

    try {
        enterBootloaderAuto(port)

        // Build a SYNC packet to send to the ESP:
        //  0x00 -> Direction
        // 0x08 -> Command
        // 0x24, 0x00 -> Size
        // checklsum and payload idk what the others do

        val data = ByteArray(36).apply {
            this[0] = 0x07
            this[1] = 0x07
            this[2] = 0x12
            this[3] = 0x20

            for (i in 4 until 36) {
                this[i] = 0x55.toByte()
            }
        }

        val header = byteArrayOf(
            0x00, 0x08, 0x24, 0x00, // dir cd size (36)
            0x00, 0x00, 0x00, 0x00 // checksum
        )

        val packet = slipEncode(header + data)
        port.write(packet, 1000)

        // Read until the ESP sends a "response" packet starting with
        // 0x01 (response) and command 0x08
        repeat(6) {

            // Somethign about skipping over the packet header
            val response = slipTryToReadPacket(800) ?: return@repeat
            if (response.size >=2 && response[0] == 0x01.toByte() && response[1] == 0x08.toByte()) {
                success = true
                return@withContext true
            }
        }
        false
    } finally {
        if (!success) SerialSession.startIO()
    }
}

suspend fun tryBootloaderSyncNoReset(): Boolean = withContext(Dispatchers.IO) {
    val port = SerialSession.port ?: return@withContext false
    SerialSession.stopIO()
    var success = false

    // SYNC Packet
    val data = ByteArray(36).apply {
        this[0] = 0x07; this[1] = 0x07; this[2] = 0x12; this[3] = 0x20
        for (i in 4 until 36) {
            this[i] = 0x55.toByte()
        }
    }

    val header = byteArrayOf(
        0x00, 0x08, 0x24, 0x00, // dir cd size (36)
        0x00, 0x00, 0x00, 0x00
    )

    val packet = slipEncode(header + data)
    fun drain(ms: Long) {
        val buffer = ByteArray(256)
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < ms) {
            val n = try { port.read(buffer, 20) } catch (_: Exception) { return }
            if (n <= 0) return
        }
    }

    try {
        // Drains the "Waiting for download" text bytes
        drain(400)

        // Sends a SYNC packet multiple times
        repeat(6) {
            try { port.write(packet, 1000) } catch (_: Exception) { }

            repeat(6) {
                val response = slipTryToReadPacket(800) ?: return@repeat
                if (response.size >=2 && response[0] == 0x01.toByte() && response[1] == 0x08.toByte()) {
                    success = true
                    return@withContext true
                }
            drain(100)
            }
        }
        false
    } finally {
        if (!success) SerialSession.startIO()
    }

}

/* COMPOSABLES */
@Composable
fun DeviceInfoDisplay(modifier: Modifier = Modifier, serialLog: String) {
    Column(modifier = modifier.background(Color.Blue), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = serialLog, modifier = modifier.padding(50.dp), color = Color.White)
    }
}

@Composable
fun PolarRoboticsHub(modifier: Modifier = Modifier) {
    var context = androidx.compose.ui.platform.LocalContext.current
    var usbManager: UsbManager
    var deviceInfo by remember {mutableStateOf("foo")}
    var serialLog by remember {mutableStateOf("")}
    var bootloaderReady by remember {mutableStateOf(false)}

    SerialSession.onText = { text ->
        serialLog += text
        if (serialLog.length > 4000) serialLog = serialLog.takeLast(4000)
    }

    SerialSession.onStatus = { msg ->
        deviceInfo = msg
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Check for devices.")
        Spacer(Modifier.height(20.dp))


        val scope = remember { CoroutineScope(Dispatchers.Main) }

        Button(onClick = {
            val manager = context.getSystemService(UsbManager::class.java)!!
            val dev = findCh340Device(manager)
            deviceInfo = if (dev == null) "No CH340 device found" else describeDevice(dev)
            openCh340OrRequestPermission(context)
        }) { Text("Open (CH340)") }

        Spacer(Modifier.height(10.dp))

        Button(onClick = {
            SerialSession.closeConnection()
        }) { Text("Close") }

        Spacer(Modifier.height(10.dp))

        Button(onClick = {
            scope.launch {
                val ok = tryBootloaderSync()
                Toast.makeText(context, if (ok) "Bootloader SYNC Success" else "SYNC failed (hold BOOT?)", Toast.LENGTH_SHORT).show()
                deviceInfo = if (ok) "Bootloader SYNC Success" else "SYNC failed — try holding BOOT while pressing this"
            }
        }) { Text("Bootloader SYNC") }

        Button(onClick = {
            scope.launch {
                val ok = tryBootloaderSyncNoReset()
                Toast.makeText(
                    context,
                    if (ok) "SYNC (no reset) success" else "SYNC (no reset) failed ",
                    Toast.LENGTH_SHORT
                ).show()
                if (ok) {
                    deviceInfo = "Bootloader connected; ready to flash"
                    bootloaderReady = true
                }
            }
        }) { Text("SYNC (no reset)") }

        Spacer(Modifier.height(10.dp))

        Button(
            onClick = {
                scope.launch {
                    // Ensure background reader is stopped during bootloader commands
                    SerialSession.stopIO()
                    val mac = withContext(Dispatchers.IO) { readMacAddressFromEfuse() }
                    if (mac == null) {
                        Toast.makeText(context, "Read MAC failed", Toast.LENGTH_SHORT).show()
                        deviceInfo = "Read MAC failed (is chip in download mode?)"
                        bootloaderReady = false
                        // Resume normal reader so app isn’t stuck
                        SerialSession.startIO()
                    } else {
                        Toast.makeText(context, "MAC: $mac", Toast.LENGTH_SHORT).show()
                        deviceInfo = "Base MAC: $mac"
                        // Keep bootloaderReady true; keep reader stopped so we can continue with flash commands next.
                    }
                }
            },
            enabled = bootloaderReady
        ) {
            Text("Read MAC")
        }


        Spacer(Modifier.height(20.dp))
        Column(Modifier
            .heightIn(min = 100.dp)
            .background(Color(0xFFFFC787))
            .widthIn(250.dp)
            .padding(10.dp)) {
            Text(text = deviceInfo)
        }
        Spacer(Modifier.height(20.dp))
        DeviceInfoDisplay(serialLog = serialLog)

        Row(modifier = modifier.fillMaxWidth()) {
            Button(onClick = {
                openCh340OrRequestPermission(context)
            }) {
                Text("Open Serial Connection")
            }

            Spacer(modifier.weight(0.5f))

            Button(onClick = {
                SerialSession.closeConnection()
            }) {
                Text("Close Serial Connection")
            }
        }

    }
}


/* PREVIEWS */
@Preview(showBackground = true)
@Composable
fun DeviceInfoDisplayPreview() {
    PolarRoboticsHubTheme {
        DeviceInfoDisplay(serialLog = "Foo")
    }
}

@Preview(showBackground = true)
@Composable
fun PolarRoboticsHubPreview() {
    PolarRoboticsHubTheme {
        PolarRoboticsHub()
    }
}