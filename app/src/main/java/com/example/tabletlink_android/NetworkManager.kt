package com.example.tabletlink_android

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NetworkManager(private val scope: CoroutineScope) {

    // MainActivity와 통신하기 위한 리스너 인터페이스
    interface NetworkListener {
        fun onConnectionSuccess(ip: String)
        fun onConnectionFailed(message: String)
        fun onConnectionLost(message: String)
    }

    private var udpSocket: DatagramSocket? = null
    private var serverAddress: InetAddress? = null
    private var listener: NetworkListener? = null

    // Coroutine Jobs
    private var senderJob: Job? = null
    private var receiverJob: Job? = null
    private var heartbeatJob: Job? = null
    private val dataChannel = Channel<ByteArray>(Channel.UNLIMITED)

    // State Management
    @Volatile
    private var isConnectionEstablished = false
    @Volatile
    private var lastPongReceivedTime: Long = 0

    // Constants
    companion object {
        private const val PORT = 9999
        private const val HEARTBEAT_INTERVAL_MS = 5000L
        private const val HEARTBEAT_TIMEOUT_MS = 10000L

        // Packet Types
        private const val PACKET_TYPE_DEVICE_INFO_REQ: Byte = 255.toByte()
        private const val PACKET_TYPE_DEVICE_INFO_ACK: Byte = 254.toByte()
        private const val PACKET_TYPE_HEARTBEAT_PING: Byte = 253.toByte()
        private const val PACKET_TYPE_HEARTBEAT_PONG: Byte = 252.toByte()
    }

    fun connect(ip: String, deviceInfo: DeviceInfo, listener: NetworkListener) {
        this.listener = listener
        disconnect() // 기존 연결 정리

        scope.launch(Dispatchers.IO) {
            try {
                serverAddress = InetAddress.getByName(ip)
                udpSocket = DatagramSocket().apply { soTimeout = 2000 }

                startReceiver()
                val handshakeSuccess = performHandshake(deviceInfo)

                if (handshakeSuccess) {
                    isConnectionEstablished = true
                    lastPongReceivedTime = System.currentTimeMillis()
                    startSender()
                    startHeartbeat()
                    withContext(Dispatchers.Main) { listener.onConnectionSuccess(ip) }
                } else {
                    withContext(Dispatchers.Main) { listener.onConnectionFailed("응답 없음") }
                    disconnect()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) { listener.onConnectionFailed(e.message ?: "알 수 없는 오류") }
                disconnect()
            }
        }
    }

    fun disconnect() {
        isConnectionEstablished = false
        senderJob?.cancel()
        receiverJob?.cancel()
        heartbeatJob?.cancel()
        udpSocket?.close()
        while(dataChannel.tryReceive().isSuccess) { } // 채널 비우기
    }

    fun sendPenData(data: ByteArray) {
        if (!isConnectionEstablished) return
        scope.launch {
            dataChannel.send(data)
        }
    }

    private suspend fun performHandshake(deviceInfo: DeviceInfo): Boolean {
        val deviceInfoPacket = createDeviceInfoPacket(deviceInfo)
        repeat(5) {
            val packet = DatagramPacket(deviceInfoPacket, deviceInfoPacket.size, serverAddress, PORT)
            udpSocket?.send(packet)
            delay(1000)
            if (isConnectionEstablished) return true
        }
        return false
    }

    private fun startReceiver() {
        receiverJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(16)
            val packet = DatagramPacket(buffer, buffer.size)

            while (isActive) {
                try {
                    udpSocket?.receive(packet)
                    when (buffer[0]) {
                        PACKET_TYPE_DEVICE_INFO_ACK -> isConnectionEstablished = true
                        PACKET_TYPE_HEARTBEAT_PONG -> lastPongReceivedTime = System.currentTimeMillis()
                    }
                } catch (e: SocketTimeoutException) {
                    // Ignore
                } catch (e: Exception) {
                    if (isActive) e.printStackTrace()
                }
            }
        }
    }

    private fun startSender() {
        senderJob = scope.launch(Dispatchers.IO) {
            for (data in dataChannel) {
                if (!isActive) break
                val packet = DatagramPacket(data, data.size, serverAddress, PORT)
                udpSocket?.send(packet)
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while(isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (System.currentTimeMillis() - lastPongReceivedTime > HEARTBEAT_TIMEOUT_MS) {
                    withContext(Dispatchers.Main) { listener?.onConnectionLost("타임아웃") }
                    disconnect()
                    break
                } else {
                    val pingPacket = createControlPacket(PACKET_TYPE_HEARTBEAT_PING)
                    val packet = DatagramPacket(pingPacket, pingPacket.size, serverAddress, PORT)
                    udpSocket?.send(packet)
                }
            }
        }
    }

    private fun createDeviceInfoPacket(deviceInfo: DeviceInfo): ByteArray {
        val buffer = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(PACKET_TYPE_DEVICE_INFO_REQ)
        buffer.putInt(deviceInfo.width)
        buffer.putInt(deviceInfo.height)
        buffer.putFloat(deviceInfo.refreshRate)
        return buffer.array()
    }

    private fun createControlPacket(type: Byte): ByteArray = byteArrayOf(type)

    data class DeviceInfo(val width: Int, val height: Int, val refreshRate: Float)
}
