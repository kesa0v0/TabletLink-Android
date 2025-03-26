package com.example.tabletlink_android

import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        Log.d("kesa", "app started")

        val button = findViewById<Button>(R.id.button).setOnClickListener {
            CoroutineScope(Dispatchers.Default).launch {
                val t = async(Dispatchers.IO){
                    a()
                }
            }
        }
    }

    fun a() {
        val socket = DatagramSocket()
        val serverAddress = InetAddress.getByName("localhost") // WPF 서버 IP
        val serverPort = 12345

        // 메시지 전송
        val message = "Hello from Android!"
        val sendData = message.toByteArray()
        val sendPacket = DatagramPacket(sendData, sendData.size, serverAddress, serverPort)
        socket.send(sendPacket)

        // 서버 응답 수신
        val receiveData = ByteArray(1024)
        val receivePacket = DatagramPacket(receiveData, receiveData.size)
        socket.receive(receivePacket)
        val receivedMessage = String(receivePacket.data, 0, receivePacket.length)

        println("서버 응답: $receivedMessage")
        socket.close()
    }
}