package com.example.tabletlink_android

import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

import java.nio.ByteBuffer
import kotlin.math.log

const val TAG = "kesa"

class Network {
    var serverAddress: InetAddress
    var serverPort: Int

    val socket: DatagramSocket = DatagramSocket()
    val receiveData = ByteArray(1024)
    var udpjob: Job? = null

    constructor(
        serverAddress: InetAddress = InetAddress.getByName("10.0.2.2"),
        serverPort: Int = 12345
    ) {
        this.serverAddress = serverAddress
        this.serverPort = serverPort
    }

    data class FrameData(
        var data: ByteArray,
        var size: Int,
        var width: Int,
        var height: Int,
        var timestamp: Long
    )

    fun bytesToFrameData(bytes: ByteArray): FrameData {
        val buffer = ByteBuffer.wrap(bytes)

        // 데이터 역 직렬화
        val size = buffer.int
        val width = buffer.int
        val height = buffer.int
        val timestamp = buffer.long

        val dataLength = bytes.size - 4 * Integer.BYTES - Long.SIZE_BYTES
        val data = ByteArray(dataLength)
        buffer.get(data)

        return FrameData(data, size, width, height, timestamp)
    }

    fun startListen() = runBlocking {

        // 메시지 전송
        //        Log.d(TAG, "a: Transmit")
        //        val message = "Hello from Android!"
        //        val sendData = message.toByteArray()
        //        val sendPacket = DatagramPacket(sendData, sendData.size, serverAddress, serverPort)
        //        socket.send(sendPacket)
        udpjob = launch(Dispatchers.IO) {
            Log.d(TAG, "a: Start Receiving")
            while (true) {
                try {
                    // 서버 응답 수신
                    val receivePacket = DatagramPacket(receiveData, receiveData.size)
                    socket.receive(receivePacket)

                    val frameData = bytesToFrameData(receiveData);
                    println("서버 응답: ${frameData.size}")
                } catch (e: Exception) {
                    Log.e(TAG, "startListen: ${e.message}")
                }
            }
        }
    }

    fun stopListen() {
        udpjob?.cancel()
        udpjob = null
        socket.close()
        Log.e(TAG, "stopListen: UDP 수신 종료", )
    }
}

class MainActivity : AppCompatActivity() {
    val server = Network()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        Log.d(TAG, "app started")

        val button = findViewById<Button>(R.id.button).setOnClickListener {
            server.startListen()
        }
    }

    override fun onDestroy() {
        server.stopListen()
        super.onDestroy()
    }

}