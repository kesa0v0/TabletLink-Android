package com.example.tabletlink_android

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.thibaultbee.srtdroid.core.Srt
import io.github.thibaultbee.streampack.core.elements.endpoints.DynamicEndpointFactory
import io.github.thibaultbee.streampack.core.pipelines.StreamerPipeline
import io.github.thibaultbee.streampack.ui.views.PreviewView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector


const val TAG = "kesa"


data class PenData(
    var x: Float,
    var y: Float,
    var pressure: Float,
    var orientation: Float,
    var tilt: Float,
    var distance: Float,

    var timestamp: Long
)

class Network {
    var isConnected = false

    var serverAddress: InetAddress = InetAddress.getByName("10.0.2.2")
    var receivePort: Int = 12346
    var sendPort: Int = 12345

    private var receiveChannel: DatagramChannel?
    var sendSocket: DatagramSocket?
    private val receiveData = ByteBuffer.allocate(65535)
    private var udpJob: Job? = null

    var surfaceView: SurfaceView? = null
    var bitmap: Bitmap? = null

    constructor(serverAddress: InetAddress, receivePort: Int, sendPort: Int) {
        this.serverAddress = serverAddress
        this.receivePort = receivePort
        this.sendPort = sendPort

        sendSocket = DatagramSocket()
        receiveChannel = DatagramChannel.open()
        receiveChannel?.configureBlocking(false)
        receiveChannel?.bind(null)
    }


    enum class PacketType(val id: Byte) {
        DISCOVER_TABLET_SERVER(0x01),
        PEN_INPUT(0x02)
    }

    fun sendPacketToByte(type: PacketType, data: PenData?): ByteArray {
        val buffer = ByteBuffer.allocate(1024)
        buffer.put(type.id)  // 1 byte

        data?.let {
            buffer.putFloat(it.x)
            buffer.putFloat(it.y)
            buffer.putFloat(it.pressure)
            buffer.putFloat(it.orientation)
            buffer.putFloat(it.tilt)
            buffer.putFloat(it.distance)

            buffer.putLong(it.timestamp)
        }

        return buffer.array().copyOf(buffer.position()) // 사용한 만큼만
    }

    fun startServer() {
        if (receiveChannel == null) {
            receiveChannel = DatagramChannel.open()
            receiveChannel?.configureBlocking(false)
            receiveChannel?.bind(InetSocketAddress(receivePort))
        }

        if (sendSocket == null) {
            sendSocket = DatagramSocket()
        }
    }

    fun discoverServer() {
        if (isConnected)
            return

        if (receiveChannel == null) {
            startServer()
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val selector = Selector.open()
                receiveChannel?.register(selector, SelectionKey.OP_READ)


                val isEmulator = true
                val serverIp = if (isEmulator)
                    InetAddress.getByName("10.0.2.2")
                else
                    InetAddress.getByName("255.255.255.255")

                // 서버에 discover 메시지 전송
                val discoveryMsg = sendPacketToByte(PacketType.DISCOVER_TABLET_SERVER, null)
                val sendBuffer = ByteBuffer.wrap(discoveryMsg)
                receiveChannel?.send(sendBuffer, InetSocketAddress(serverIp, sendPort))

                // 서버 응답 대기
                val timeout = 3000L
                val startTime = System.currentTimeMillis()

                while (System.currentTimeMillis() - startTime < timeout) {
                    if (selector.select(100) > 0) {
                        val keys = selector.selectedKeys()
                        val it = keys.iterator()
                        while (it.hasNext()) {
                            val key = it.next()
                            it.remove()

                            if (key.isReadable) {
                                receiveChannel?.receive(receiveData)
                                receiveData.flip()

                                val msg = String(receiveData.array(), 0, receiveData.limit())
                                if (msg.startsWith("TABLET_SERVER_ACK")) {
                                    val parts = msg.split(":")
                                    val ip = InetAddress.getByName(parts[1])
                                    val port = parts[2].toInt()
                                    onDiscovered(ip, port)
                                    return@launch
                                }
                            }
                        }
                    }
                }
                Log.w(TAG, "discoverServer: No server found")
            } catch (e: Exception) {
                Log.e(TAG, "discoverServer: ${e.stackTraceToString()}")
            }
        }
    }

    fun onDiscovered(ip: InetAddress, port: Int) {
        Log.d(TAG, "서버 발견: $ip:$port")
        this.serverAddress = ip
        this.sendPort = port
    }


    fun testSend() {
        if (sendSocket == null) {
            sendSocket = DatagramSocket()
        }
        var sendData = sendPacketToByte(
            PacketType.PEN_INPUT, PenData(
                0f, 0f, 0f, 0f, 0f, 0f,
                System.currentTimeMillis()
            )
        )
        CoroutineScope(Dispatchers.IO).async {
            val packet = DatagramPacket(
                sendData,
                sendData.size,
                serverAddress,
                sendPort
            )
            sendSocket?.send(packet)
            Log.d(TAG, "send packet")
        }
    }

    fun stopListen() {
        isConnected = false
        udpJob?.cancel()
        udpJob = null
        sendSocket?.close()
        sendSocket = null
        Log.d(TAG, "stopListen: UDP 수신 종료")
    }
}

class MainActivity : AppCompatActivity() {
    val server = Network(InetAddress.getByName("10.0.2.2"), 12346, 12345)
    private lateinit var surfaceView: SurfaceView
    private lateinit var codec: MediaCodec
    private lateinit var surface: Surface


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        Srt.


        findViewById<Button>(R.id.Discover).setOnClickListener {
            server.discoverServer()
        }

        findViewById<Button>(R.id.SendPen).setOnClickListener {
            server.testSend()
        }

        val s = findViewById<SwitchCompat>(R.id.switch1)
        s.setOnClickListener {
            if (s.isChecked) {

            } else {
                server.stopListen()
            }
        }

    }

    override fun onDestroy() {
        server.stopListen()
        super.onDestroy()
        CoroutineScope(Dispatchers.IO).launch {
            streamer.stopStream()
        }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event == null) return false

        val toolType = event.getToolType(0)

        if (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER) {
            Log.d(TAG, "onTouchEvent: toolType: $toolType")

            val x = event.getX(0)
            val y = event.getY(0)
            val pressure = event.getPressure(0)
            val orientation = event.getOrientation(0)
            val tilt = event.getAxisValue(MotionEvent.AXIS_TILT, 0)
            val distance = event.getAxisValue(MotionEvent.AXIS_DISTANCE, 0)
            val timestamp = System.currentTimeMillis()

            val penData = PenData(x, y, pressure, orientation, tilt, distance, timestamp)
            val sendData = server.sendPacketToByte(Network.PacketType.PEN_INPUT, penData)

            CoroutineScope(Dispatchers.IO).launch {
                val packet = DatagramPacket(
                    sendData,
                    sendData.size,
                    server.serverAddress,
                    server.sendPort
                )
                server.sendSocket?.send(packet)
                Log.d(TAG, "send packet")
            }
        }

        return super.onTouchEvent(event)
    }
}