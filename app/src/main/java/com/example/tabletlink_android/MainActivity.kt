package com.example.tabletlink_android

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.SurfaceView
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.jpountz.lz4.LZ4Factory
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import androidx.core.graphics.createBitmap


const val TAG = "kesa"

class Network {
    var isConnected = false

    var serverAddress: InetAddress = InetAddress.getByName("10.0.2.2")
    var receivePort: Int = 12346
    var sendPort: Int = 12345

    private var receiveChannel: DatagramChannel?
    private var sendSocket: DatagramSocket?
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

    data class FrameData(
        var data: ByteArray,
        var width: Int,
        var height: Int,
        var frameRate: Int,

        var size: Int,
        var timestamp: Long
    )

    data class PenData(
        var x: Int,
        var y: Int,
        var pressure: Int,
        var timestamp: Long
    )

    fun bytesToFrameData(bytes: ByteArray): FrameData {
        val buffer = ByteBuffer.wrap(bytes)

        // 데이터 역 직렬화
        val width = buffer.int
        val height = buffer.int
        val dataRate = buffer.int
        val size = buffer.int
        val timestamp = buffer.long

        val dataLength = bytes.size - 4 * Integer.BYTES - Long.SIZE_BYTES
        val data = ByteArray(dataLength)
        buffer.get(data)

        return FrameData(data, width, height, dataRate, size, timestamp)
    }

    enum class PacketType(val id: Byte) {
        DISCOVER_TABLET_SERVER(0x01),
        PEN_INPUT(0x02)
    }

    fun sendPacketToByte(type: PacketType, data: PenData?): ByteArray {
        val buffer = ByteBuffer.allocate(1024)
        buffer.put(type.id)  // 1 byte

        data?.let {
            buffer.putInt(it.x)
            buffer.putInt(it.y)
            buffer.putInt(it.pressure)
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

    fun startListen() {
        isConnected = true

        udpJob = CoroutineScope(Dispatchers.IO).launch {
            val selector = Selector.open()
            receiveChannel?.register(selector, SelectionKey.OP_READ)

            Log.d(TAG, "a: Start Receiving")
            while (isConnected) {
                try {
                    if (selector.select(5) > 0) {
                        val keys = selector.selectedKeys()
                        val it = keys.iterator()
                        while (it.hasNext()) {
                            val key = it.next()
                            it.remove()

                            if (key.isReadable) {
                                receiveData.clear()
                                receiveChannel?.receive(receiveData)
                                receiveData.flip()

                                val receivedData = ByteArray(receiveData.remaining())
                                receiveData.get(receivedData)

                                val frameData = bytesToFrameData(receivedData)

                                Log.d(
                                    TAG,
                                    "startListen: width: ${frameData.width}, height: ${frameData.height}, timestamp: ${frameData.timestamp}"
                                )
                                Log.d(
                                    TAG,
                                    "startListen: latency: ${System.currentTimeMillis() - frameData.timestamp}"
                                )

                                // 비동기 압축 해제
                                launch(Dispatchers.Default) {
                                    val decompressor = LZ4Factory.fastestInstance().fastDecompressor()
//                                    val decompressedData = decompressor.decompress(frameData.data, frameData.size)

                                    withContext(Dispatchers.Main) {
//                                        xorScreen(decompressedData, frameData.width, frameData.height, frameData.timestamp)
                                        xorScreen(frameData, frameData.width, frameData.height, frameData.timestamp)
                                    }
                                }
                            }
                        }
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "startListen: ${e.stackTraceToString()}")
                }
            }
        }
    }

//    var previousFrame: ByteArray = ByteArray(1920 * 1080 * 4) // 초기화
    private fun CoroutineScope.xorScreen(
        screenData: FrameData,
        width: Int,
        height: Int,
        timestamp: Long
    ) {
        Log.d(TAG, "updateScreen: width: $width, height: $height, timestamp: $timestamp")

//        val newFrame = ByteArray(previousFrame.size)

//        Utils.xorFrames(newFrame, previousFrame, screenData as ByteArray)
//        previousFrame = screenData

        // Bitmap 생성
        val bmp = createBitmap(width, height)
        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(screenData.data))

        surfaceView?.holder?.lockCanvas()?.let { canvas ->
            canvas.drawBitmap(bmp, 0f, 0f, null)
            surfaceView?.holder?.unlockCanvasAndPost(canvas)
        }
    }

    fun testSend() {
        if (sendSocket == null) {
            sendSocket = DatagramSocket()
        }
        var sendData = sendPacketToByte(
            PacketType.PEN_INPUT, PenData(
                0, 0, 0,
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
        receiveChannel?.close()
        receiveChannel = null
        Log.d(TAG, "stopListen: UDP 수신 종료")
    }
}

class MainActivity : AppCompatActivity() {
    val server = Network(InetAddress.getByName("10.0.2.2"), 12346, 12345)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        Log.d(TAG, "app started")

        val surfaceView = findViewById<SurfaceView>(R.id.surfaceView)
        val surfaceHolder = surfaceView.holder
        server.surfaceView = surfaceView


        findViewById<Button>(R.id.Discover).setOnClickListener {
            server.discoverServer()
        }

        findViewById<Button>(R.id.SendPen).setOnClickListener {
            server.testSend()
        }

        val s = findViewById<SwitchCompat>(R.id.switch1)
        s.setOnClickListener {
            if (s.isChecked) {
                server.startListen()
            } else {
                server.stopListen()
            }
        }
    }

    override fun onDestroy() {
        server.stopListen()
        super.onDestroy()
    }
}