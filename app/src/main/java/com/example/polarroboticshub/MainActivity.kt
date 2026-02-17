package com.example.polarroboticshub

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat.getSystemService
import com.example.polarroboticshub.ui.theme.PolarRoboticsHubTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PolarRoboticsHubTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PolarRoboticsHub(Modifier.padding(innerPadding))
                }
            }
        }
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

/* COMPOSABLES */
@Composable
fun DeviceInfoDisplay(modifier: Modifier = Modifier) {

}

@Composable
fun PolarRoboticsHub(modifier: Modifier = Modifier) {
    var context = androidx.compose.ui.platform.LocalContext.current
    var usbManager: UsbManager
    var deviceInfo by remember {mutableStateOf("foo")}

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Check for devices.")
        Spacer(Modifier.height(20.dp))
        Button(onClick = {

            usbManager = context.getSystemService(UsbManager::class.java)!!
            getDeviceData(context, usbManager.deviceList)
            deviceInfo = describeDevice(usbManager.deviceList.values.first())

            if (detectESP32Board(usbManager.deviceList.values.first())) {
                Toast.makeText(context, "ESP32 Board Detected", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "No ESP32 Board Detected", Toast.LENGTH_SHORT).show()
            }

        }) { Text("Detect Devices") }
        Spacer(Modifier.height(20.dp))
        Column(Modifier.heightIn(min = 100.dp).background(Color(0xFFFFC787)).widthIn(250.dp).padding(10.dp)) {
            Text(text = deviceInfo)
        }
    }
}


/* PREVIEWS */
@Preview(showBackground = true)
@Composable
fun DeviceInfoDisplayPreview() {
    PolarRoboticsHubTheme {
        DeviceInfoDisplay()
    }
}

@Preview(showBackground = true)
@Composable
fun PolarRoboticsHubPreview() {
    PolarRoboticsHubTheme {
        PolarRoboticsHub()
    }
}