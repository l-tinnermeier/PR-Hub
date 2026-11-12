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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CancellationException


private val esp = KotlinESP()
private val decoder = SlipDecoder()

private const val ACTION_USB_PERMISSION = "com.example.polarroboticshub.USB_PERMISSION"

private object SerialSession {
    var connection: android.hardware.usb.UsbDeviceConnection? = null
    var port: UsbSerialPort? = null
    var device: UsbDevice? = null
    var bootloaderReady by mutableStateOf(false)

    @Volatile private var executor: ExecutorService? = null
    private var mainHandler = Handler(Looper.getMainLooper())

    @Volatile var onText: ((String) -> Unit)? = null
    @Volatile var onStatus: ((String) -> Unit)? = null

    fun startIO() {
        val port = port ?: return
        stopIO()


        executor = Executors.newSingleThreadExecutor().also { exec ->
            exec.execute {
                val buffer = ByteArray(1024)
                while (!exec.isShutdown && !Thread.currentThread().isInterrupted) {
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
                            if (executor === exec && !exec.isShutdown) {
                                onStatus?.invoke("Serial read error: ${e.message}")
                                closeConnection()
                            }
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

    // A specific stopping function that lets the current read finish before cutting
    // Prevents two readers from opening on the same port
    fun pauseMonitorForBootloader(): Boolean {
        val reader = executor ?: return true
        reader.shutdown()

        return try {
            reader.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) {
            Thread.currentThread().interrupt()
            false
        }
    }

    fun closeConnection() {
        bootloaderReady = false
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
        (vendorId == 0x1A86 && productId == 0x7523) || // CH340
        (vendorId == 0x10C4 && productId == 0xEA60)    // CP210x
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

// Reading SLIP packets sent from ESP
private fun slipTryToReadPacket(timeoutMsTotal: Long = 1500): ByteArray? {
    // Grabbing current serial connection
    val port = SerialSession.port ?: return null

    // Checking for remnants from a previous USB read
    decoder.nextPacket()?.let { return it }

    // Reading information continuously for the given value of `timeoutMSTotal`
    val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMsTotal
    val buffer = ByteArray(1024)

    while (true) {
        val remaining = deadline - android.os.SystemClock.elapsedRealtime()

        if (remaining <= 0) return null

        val count = try { port.read(buffer, minOf(remaining, 50L).toInt()) }
                    catch (_: Exception) { return null }

        if (count > 0) {
            decoder.feed(buffer, count)
            decoder.nextPacket()?.let { return it }
        }
    }
    return null
}

// Send a bootloader command and wait for the matching response packet
// NOTE: Caller should have the chip in "download" mode
private fun sendCommand(command: Int, payload: ByteArray, timeoutMs: Long = 3000): ByteArray? {
    val port = SerialSession.port ?: return null
    val packet = esp.generateCommand(command, payload)
    try {
        port.write(packet, 1000)
    } catch (_: Exception) {
        return null
    }

    val startTime = System.currentTimeMillis()
    while (System.currentTimeMillis() - startTime < timeoutMs) {
        val response = slipTryToReadPacket(800) ?: continue

        //  Response header: dir, cmd, size(2), value(4), data... (now handled by checkROMReply)
        when (esp.checkROMReply(response, command)) {
            KotlinESP.Reply.OK -> return response
            KotlinESP.Reply.OTHER_COMMAND -> continue
            KotlinESP.Reply.MALFORMED -> return null
            KotlinESP.Reply.DEVICE_ERROR -> {
                // Pulling error code from the third-to-last byte
                val error = response[response.size - 3].toInt() and 0xFF
                android.util.Log.w("EspROM", "Command %02X failed: %02X".format(command, error))
                return null
            }
        }
    }
    return null
}

// To send SPI_ATTACH, send 0x0D to the ESP with two four-byte all-zero words (to select the default flash config)
private fun attachDefaultFlash(): Boolean {
    return sendCommand(0x0D, ByteArray(8)) != null
}

// Following the ESP protocol's format for preparing to flash:
//  https://github.com/espressif/esptool/blob/master/esptool/loader.py
private fun setFlashParameters(sizeBytes: Int): Boolean {
    require (sizeBytes > 0)
    val payload = le32(0) +             // Flash ID
                  le32(sizeBytes) +     // Configured flash size
                  le32(64 * 1024) +     // Block size
                  le32(4 * 1024) +      // Sector size
                  le32(256) +           // Page size
                  le32(0xFFFF)          // Status mask

    return sendCommand(0x0B, payload) != null;
}

//  READ_REG (0x0A) - the payload is a 32-bit address; response puts the read value in bytes [4..7]
private fun readRegister(address: Int): Int? {
    val response = sendCommand(0x0A, le32(address), timeoutMs = 3000) ?: return null

    if (response.size < 8) return null
    return esp.decodeRegisterResponse(response)
}

//  The ESP32's base MAC address is stored in eFuse BLK0 read-data registers:
//      - bits [31:0] are stored in EFUSE_BLK0_RDATA1_REG
//      - bits [55:32] are stores in EFUSE_BLK0_RDATA_2_REG[23:0]

// something aint right here, byte order looks wrong
// supposed MAC address (pocket ESP): 78:1C:3C:2D:67:D8
private fun readMacAddressFromEfuse(): String? {
    val w1 = readRegister(0x3FF5A004) ?: return null //     EFUSE_BLK0_RDATA1_REG
    val w2 = readRegister(0x3FF5A008) ?: return null //     EFUSE_BLK0_RDATA2_REG

    return esp.decodeMac(w1, w2);
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
    if (!SerialSession.pauseMonitorForBootloader()) return@withContext false
    decoder.reset()
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

        val packet = esp.encodeSlip(header + data)
        port.write(packet, 1000)

        // Read until the ESP sends a "response" packet starting with
        // 0x01 (response) and command 0x08
        repeat(6) {

            // Somethign about skipping over the packet header
            val response = slipTryToReadPacket(800) ?: return@repeat
            if (esp.checkROMReply(response, 0x08) == KotlinESP.Reply.OK) {
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
    if (!SerialSession.pauseMonitorForBootloader()) return@withContext false
    decoder.reset()
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

    val packet = esp.encodeSlip(header + data)
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
                if (esp.checkROMReply(response, 0x08) == KotlinESP.Reply.OK) {
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

// diabolical flashing function of doom and despair
// Caller must own the serial connection exclusively:
// busy=true, successful SYNC, and background reader stopped.
//
// regionSize is the available destination region, not the file size.
// onProgress runs on Dispatchers.IO, but can switch to Dispatchers.Main for UI updates
private suspend fun flashBinary(image: ByteArray, offset: Int, regionSize: Int, onProgress: suspend (written: Int, total: Int) -> Unit = { _, _ -> }):
                                Unit = withContext(Dispatchers.IO) {
    val flashSize = 2 * 1024 * 1024
    val blockSize = 1024

    require(image.isNotEmpty() && image.size <= flashSize)
    require(offset >= 0 && offset % 4096 == 0)
    require(regionSize > 0 && regionSize % 4096 == 0)

    val eraseSize = ((image.size + 4095) / 4096) * 4096

    require(eraseSize <= regionSize) {"Image exceeds the selected destination region"}
    require(offset.toLong() + regionSize <= flashSize.toLong()) {"Destination exceeds the configured 2 MB flash"}

    val activePort = checkNotNull(SerialSession.port) {"No serial connection"}

    suspend fun requireActiveConnection() {
        currentCoroutineContext().ensureActive()
        check(SerialSession.port === activePort) {"Serial connection changed during flashing"}
    }

    suspend fun command(opcode: Int, payload: ByteArray, timeoutMs: Long, failureMessage: String): ByteArray {
        requireActiveConnection()
        return checkNotNull(sendCommand(opcode, payload, timeoutMs)) {failureMessage}
    }

    command(0x0D, ByteArray(8), 3000, "Could not attach flash")

    // Configure flash for this 2 MB test profile.
    requireActiveConnection()
    check(setFlashParameters(flashSize)) {"Could not configure flash"}

    command(0x02, esp.buildFlashBeginPayload(image.size, offset), 120_000, "FLASH_BEGIN failed or timed out")

    onProgress(0, image.size)

    val blockCount = (image.size + blockSize - 1) / blockSize

    for (sequence in 0 until blockCount) {
        val start = sequence * blockSize
        val end = minOf(start + blockSize, image.size)

        // Always transmit a full block. Unused bytes stay erased (0xFF).
        val block = ByteArray(blockSize) { 0xFF.toByte() }
        image.copyInto(destination = block, destinationOffset = 0, startIndex = start, endIndex = end)

        command(0x03, esp.buildFlashDataPacket(sequence, block), 5000, "FLASH_DATA failed at block $sequence of $blockCount")

        onProgress(end, image.size)
    }

    // Verify the original file bytes, excluding final-block padding.
    val reply = command(
        0x13,
        le32(offset) + le32(image.size) + le32(0) + le32(0),
        60_000,
        "Flash verification command failed"
    )

    // Classic ESP32 ROM: 8-byte header + 32 ASCII MD5 bytes
    // + 4 status bytes. sendCommand already checked the status.
    check(reply.size == 44) {"Unexpected MD5 response length: ${reply.size}"}

    val actualMd5 = String(reply, 8, 32, Charsets.US_ASCII)
    val expectedMd5 = MessageDigest.getInstance("MD5").digest(image).joinToString("") {
        "%02x".format(it.toInt() and 0xFF)
    }

    check(actualMd5.equals(expectedMd5, ignoreCase = true)) {"Verification failed: expected $expectedMd5, received $actualMd5"}
}

/* COMPOSABLES */
@Composable
fun DeviceInfoDisplay(modifier: Modifier = Modifier, serialLog: String) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(240.dp)
            .background(Color.Blue)
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        Text(text = serialLog, color = Color.White)
    }
}

@Composable
fun PolarRoboticsHub(modifier: Modifier = Modifier) {
    var context = androidx.compose.ui.platform.LocalContext.current
    var usbManager: UsbManager
    var deviceInfo by remember {mutableStateOf("foo")}
    var serialLog by remember {mutableStateOf("")}
    var busy by remember {mutableStateOf(false)}

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


        val scope = rememberCoroutineScope()

        Button(onClick = {
            val manager = context.getSystemService(UsbManager::class.java)!!
            val dev = findCh340Device(manager)
            deviceInfo = if (dev == null) "No CH340 device found" else describeDevice(dev)
            openCh340OrRequestPermission(context)
        }, enabled = !busy) { Text("Open (CH340)") }

        Spacer(Modifier.height(10.dp))

        Button(onClick = {
            SerialSession.closeConnection()
        }, enabled = !busy) { Text("Close") }

        Spacer(Modifier.height(10.dp))

        Button(onClick = {
            if (!busy) {
                busy = true
                val syncingPort = SerialSession.port
                SerialSession.bootloaderReady = false
                scope.launch {
                    try {
                        val ok = tryBootloaderSync()
                        SerialSession.bootloaderReady = ok && syncingPort != null && SerialSession.port === syncingPort
                        Toast.makeText(context, if (ok) "Bootloader SYNC Success" else "SYNC failed (hold BOOT?)", Toast.LENGTH_SHORT).show()
                        deviceInfo = if (ok) "Bootloader SYNC Success" else "SYNC failed — try holding BOOT while pressing this"
                    } finally {
                        busy = false
                    }
                }
            }
        },
            enabled = !busy) { Text("Bootloader SYNC") }

        Button(onClick = {
            if (!busy) {
                busy = true
                val syncingPort = SerialSession.port
                SerialSession.bootloaderReady = false
                scope.launch {
                    try {
                        val ok = tryBootloaderSyncNoReset()
                        SerialSession.bootloaderReady = ok && syncingPort != null && SerialSession.port === syncingPort
                        Toast.makeText(
                            context,
                            if (ok) "SYNC (no reset) success" else "SYNC (no reset) failed ",
                            Toast.LENGTH_SHORT
                        ).show()
                        if (ok) {
                            deviceInfo = "Bootloader connected; ready to flash"
                        }
                    } finally {
                        busy = false
                    }
                }
            }
        },
            enabled = !busy
        ) { Text("SYNC (no reset)") }

        Spacer(Modifier.height(10.dp))

        Button(
            onClick = {
                if (!busy) {
                    busy = true
                    scope.launch {
                        try {
                            val mac = withContext(Dispatchers.IO) { readMacAddressFromEfuse() }
                            if (mac == null) {
                                Toast.makeText(context, "Read MAC failed", Toast.LENGTH_SHORT).show()
                                deviceInfo = "Read MAC failed (is chip in download mode?)"
                                SerialSession.bootloaderReady = false
                                // Resume normal reader so app isn’t stuck
                                SerialSession.startIO()
                            } else {
                                Toast.makeText(context, "MAC: $mac", Toast.LENGTH_SHORT).show()
                                deviceInfo = "Base MAC: $mac"
                                // Keep bootloaderReady true; keep reader stopped so we can continue with flash commands next.
                            }
                        } finally {
                            busy = false
                        }
                    }
                }
            },
            enabled = SerialSession.bootloaderReady && !busy
        ) {
            Text("Read MAC")
        }

        // nightmareish stack of brackets
        Button(
            enabled = SerialSession.bootloaderReady && !busy,
            onClick = {
                if (!busy) {
                    busy = true
                    scope.launch {
                        try {
                            deviceInfo = withContext(Dispatchers.IO) {
                                when {
                                    !attachDefaultFlash() -> "Flash attaching failed"
                                    !setFlashParameters(2 * 1024 * 1024) -> "Setting flash parameters failed"
                                    else -> "Flash prepared (2MB)"
                                }
                            }
                        } finally {
                            busy = false
                        }
                    }
                }
            }
        ) {
            Text("Prepare flash")
        }

        // button of doom and despair
        Button(
            enabled = SerialSession.bootloaderReady && !busy,
            onClick = {
                if (!busy && SerialSession.bootloaderReady) {
                    busy = true

                    // Capture the connection for the entire three-file operation.
                    val flashingPort = SerialSession.port
                    SerialSession.bootloaderReady = false

                    scope.launch {
                        var stage = "Loading heartbeat files"

                        try {
                            deviceInfo = stage

                            data class FlashImage(
                                val name: String,
                                val offset: Int,
                                val regionSize: Int,
                                val bytes: ByteArray
                            )

                            // Load and validate ALL files before erasing anything.
                            val images = withContext(Dispatchers.IO) {
                                val layout = listOf(
                                    Triple("bootloader.bin", 0x1000, 0x7000),
                                    Triple("partition-table.bin", 0x8000, 0x1000),
                                    Triple("heartbeat.bin", 0x10000, 0x100000)
                                )

                                layout.map { (name, offset, regionSize) ->
                                    val bytes = context.assets
                                        .open("heartbeat/$name")
                                        .use { it.readBytes() }

                                    require(bytes.isNotEmpty()) {
                                        "$name is empty"
                                    }
                                    require(bytes.size <= regionSize) {
                                        "$name exceeds its destination region"
                                    }

                                    FlashImage(name, offset, regionSize, bytes)
                                }
                            }

                            for (image in images) {
                                check(
                                    flashingPort != null &&
                                            SerialSession.port === flashingPort
                                ) {
                                    "Serial connection changed"
                                }

                                stage = image.name
                                deviceInfo = "${image.name}: preparing flash…"

                                flashBinary(
                                    image = image.bytes,
                                    offset = image.offset,
                                    regionSize = image.regionSize
                                ) { written, total ->
                                    withContext(Dispatchers.Main) {
                                        val percent =
                                            (written.toLong() * 100 / total).toInt()

                                        deviceInfo = if (written == total) {
                                            "${image.name}: verifying…"
                                        } else {
                                            "${image.name}: $percent%"
                                        }
                                    }
                                }
                            }

                            deviceInfo =
                                "All 3 files verified! Close and reopen serial, " +
                                        "then tap EN without holding BOOT."

                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            deviceInfo =
                                "Flash failed during $stage: " +
                                        (e.message ?: e.javaClass.simpleName)
                        } finally {
                            SerialSession.bootloaderReady = false
                            busy = false
                        }
                    }
                }
            }
        ) {
            Text("Flash heartbeat")
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
            }, enabled = !busy) {
                Text("Open Serial Connection")
            }

            Spacer(modifier.weight(0.5f))

            Button(onClick = {
                SerialSession.closeConnection()
            }, enabled = !busy) {
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