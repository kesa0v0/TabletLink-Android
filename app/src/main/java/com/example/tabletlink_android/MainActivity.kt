package com.example.tabletlink_android

import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
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
    companion object {
        const val TAG = "Network"
    }

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

    fun discoverServer(onFound: (InetAddress, Int) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                sendSocket.broadcast = true

                val discoveryMsg = "DISCOVER_TABLET_SERVER".toByteArray()
                val packet = DatagramPacket(
                    discoveryMsg,
                    discoveryMsg.size,
                    InetAddress.getByName("10.0.2.2"),  // TODO: 에뮬 아닐때는 이거 바꿔야함 ㅇㅇ
                    sendPort // 서버가 듣고 있는 브로드캐스트 포트
                )
                sendSocket.send(packet)

                // 응답 대기
                val buf = ByteArray(1024)
                val response = DatagramPacket(buf, buf.size)
                receiveSocket?.soTimeout = 3000

                try {
                    receiveSocket?.receive(response)
                    Log.d(TAG, "서버 발견: ${response.address}:${response.port}")
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
            catch (e: Exception) {
                Log.e(TAG, "discoverServer: ${e.message}")
            }

        }
    }

    fun requestConnection() {
        CoroutineScope(Dispatchers.IO).launch {
            val connectMsg = "CONNECT"
            val packet = DatagramPacket(
                connectMsg.toByteArray(),
                connectMsg.length,
                serverAddress,
                sendPort // WPF 수신 포트
            )
            sendSocket.send(packet)
            Log.d(TAG, "Sent connection request to WPF")
        }
    }

    fun startListen() {
        udpJob = CoroutineScope(Dispatchers.IO).launch {
            Log.d(TAG, "a: Start Receiving")
            while (true) {
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

    fun testSend(message: String) {
        CoroutineScope(Dispatchers.IO).async {
            val packet = DatagramPacket(
                message.toByteArray(),
                message.length,
                serverAddress,
                12345
            )
            sendSocket.send(packet)
            Log.d(TAG, "send packet")
        }
    }

    fun stopListen() {
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
        server.startListen()

        findViewById<Button>(R.id.button).setOnClickListener {
            server.discoverServer { ip, port ->
                Log.d(TAG, "서버 발견: $ip:$port")
                server.serverAddress = ip
                server.sendPort = port
                server.testSend("Hello from Android!")
            }
        }
    }

    override fun onDestroy() {
        server.stopListen()
        super.onDestroy()
    }

}