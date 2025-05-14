package com.example.tabletlink_android

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector


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


     fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isUp && !networkInterface.isLoopback) {
                    val inetAddresses = networkInterface.inetAddresses
                    while (inetAddresses.hasMoreElements()) {
                        val inetAddress = inetAddresses.nextElement()
                        if (inetAddress is Inet4Address) {
                            return inetAddress.hostAddress
                        }
                    }
                }
            }
        } catch (e: SocketException) {
            e.printStackTrace()
        }
        return "IP not found"
    }

}
