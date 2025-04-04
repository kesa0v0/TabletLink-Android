package com.example.tabletlink_android

import android.os.Bundle
import android.util.Log
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

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

import java.nio.ByteBuffer

const val TAG = "kesa"

class Network {
    var isConnected = false

    var serverAddress: InetAddress = InetAddress.getByName("10.0.2.2")
    var receivePort: Int = 12346
    var sendPort:Int = 12345

    private var receiveSocket: DatagramSocket? = null
    private var sendSocket: DatagramSocket = DatagramSocket()
    private val receiveData = ByteArray(65535)
    private var udpJob: Job? = null

    constructor(serverAddress: InetAddress, receivePort: Int, sendPort: Int) {
        this.serverAddress = serverAddress
        this.receivePort = receivePort
        this.sendPort = sendPort
        receiveSocket = DatagramSocket(receivePort)
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


    fun discoverServer(onFound: (InetAddress, Int) -> Unit) {
        if (isConnected)
            return

        CoroutineScope(Dispatchers.IO).launch {
            receiveSocket?.broadcast = true

            var discoveryMsg = sendPacketToByte(PacketType.DISCOVER_TABLET_SERVER, null)
            val packet = DatagramPacket(
                discoveryMsg,
                discoveryMsg.size,
                InetAddress.getByName("10.0.2.2"),  // TODO: 에뮬 아닐때는 이거 바꿔야함 ㅇㅇ
                sendPort // 서버가 듣고 있는 브로드캐스트 포트
            )
            receiveSocket?.send(packet)

            // 응답 대기
            val buf = ByteArray(1024)
            val response = DatagramPacket(buf, buf.size)
            receiveSocket?.soTimeout = 3000

            try {
                receiveSocket?.receive(response)
                val msg = String(response.data, 0, response.length)
                if (msg.startsWith("TABLET_SERVER_ACK")) {
                    val parts = msg.split(":")
                    val ip = InetAddress.getByName(parts[1])
                    val port = parts[2].toInt()
                    onFound(ip, port)
                }
            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "서버를 찾지 못함")
            }

            sendSocket.broadcast = false
        }
    }

    fun startListen() {
        isConnected = true
        udpJob = CoroutineScope(Dispatchers.IO).launch {
            Log.d(TAG, "a: Start Receiving")
            while (isConnected) {
                try {
                    // 서버 응답 수신 대기
                    val receivePacket = DatagramPacket(receiveData, receiveData.size)
                    receiveSocket?.receive(receivePacket)

                    val frameData = bytesToFrameData(receivePacket.data)
                    Log.d(TAG, "data received: ${frameData.data.size} bytes")
                    Log.d(TAG, "latency: ${System.currentTimeMillis() - frameData.timestamp} ms")
                } catch (e: Exception) {
                    Log.e(TAG, "startListen: ${e.message}")
                }
            }
        }
    }

    fun testSend() {
        var sendData = sendPacketToByte(PacketType.PEN_INPUT, PenData(0, 0, 0,
            System.currentTimeMillis()))
        CoroutineScope(Dispatchers.IO).async {
            val packet = DatagramPacket(
                sendData,
                sendData.size,
                serverAddress,
                sendPort
            )
            sendSocket.send(packet)
            Log.d(TAG, "send packet")
        }
    }

    fun stopListen() {
        isConnected = false
        udpJob?.cancel()
        udpJob = null
        sendSocket.close()
        receiveSocket?.close()
        Log.e(TAG, "stopListen: UDP 수신 종료" )
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

        findViewById<Button>(R.id.button).setOnClickListener {
            server.testSend()
        }

        val s = findViewById<SwitchCompat>(R.id.switch1)
        s.setOnClickListener {
            if (s.isChecked)
            server.discoverServer { ip, port ->
                Log.d(TAG, "서버 발견: $ip:$port")
                server.serverAddress = ip
                server.sendPort = port
                server.startListen()
            }
            else
            {
                server.stopListen()
            }
        }
    }

    override fun onDestroy() {
        server.stopListen()
        super.onDestroy()
    }

}