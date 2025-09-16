package com.example.tabletlink_android

import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Manages UDP communication with the Windows server, including connection,
 * data transmission, and connection health monitoring (heartbeat).
 *
 * @param scope The CoroutineScope to launch background tasks (e.g., from lifecycleScope).
 */
class NetworkManager(private val scope: CoroutineScope) {

    /**
     * Interface for communicating network state changes back to the UI.
     */
    interface NetworkListener {
        fun onConnectionSuccess(ip: String)
        fun onConnectionFailed(message: String)
        fun onConnectionLost(message: String)
    }

    private var udpSocket: DatagramSocket? = null
    private var serverAddress: InetAddress? = null
    private var listener: NetworkListener? = null

    // Coroutine Jobs for managing concurrent tasks.
    private var receiverJob: Job? = null
    private var heartbeatJob: Job? = null

    @Volatile
    private var isConnectionEstablished = false
    @Volatile
    private var lastPongReceivedTime: Long = 0

    companion object {
        private const val PORT = 9999
        private const val HEARTBEAT_INTERVAL_MS = 3000L
        private const val HEARTBEAT_TIMEOUT_MS = 10000L
        private const val HANDSHAKE_TIMEOUT_MS = 2000
        private const val HANDSHAKE_RETRIES = 5

        // Packet type constants must match the server implementation.
        private const val PACKET_TYPE_DEVICE_INFO_REQ: Byte = -1 // 255
        private const val PACKET_TYPE_DEVICE_INFO_ACK: Byte = -2 // 254
        private const val PACKET_TYPE_HEARTBEAT_PING: Byte = -3 // 253
        private const val PACKET_TYPE_HEARTBEAT_PONG: Byte = -4 // 252
    }

    /**
     * Attempts to connect to the server.
     * This involves a handshake process and starting background jobs for sending,
     * receiving, and heartbeat monitoring.
     */
    fun connect(ip: String, deviceInfo: DeviceInfo, listener: NetworkListener) {
        this.listener = listener
        // If a previous connection job is active, disconnect first.
        if (receiverJob?.isActive == true || heartbeatJob?.isActive == true) {
            disconnect()
        }

        scope.launch(Dispatchers.IO) {
            try {
                serverAddress = InetAddress.getByName(ip)
                udpSocket = DatagramSocket().apply { soTimeout = HANDSHAKE_TIMEOUT_MS }

                startReceiver() // Start listening for ACK immediately.
                val handshakeSuccess = performHandshake(deviceInfo)

                if (handshakeSuccess) {
                    withContext(Dispatchers.Main) { listener.onConnectionSuccess(ip) }
                    startHeartbeat()
                } else {
                    withContext(Dispatchers.Main) { listener.onConnectionFailed("Server not responding") }
                    disconnect()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) { listener.onConnectionFailed(e.message ?: "Unknown error") }
                disconnect()
            }
        }
    }

    /**
     * Closes the socket and cancels all running coroutines.
     */
    fun disconnect() {
        isConnectionEstablished = false
        // Cancel jobs in a non-blocking way.
        receiverJob?.cancel()
        heartbeatJob?.cancel()
        udpSocket?.close()
    }

    /**
     * Sends pen data to the server in a new background task to minimize latency.
     * @param data The raw byte array packet to send.
     */
    fun sendPenData(data: ByteArray) {
        if (!isConnectionEstablished) return

        // Launch a new coroutine for each packet to send it immediately
        // without waiting for a channel or a single sender loop.
        // This can help reduce latency for real-time data.
        // 실시간 데이터 지연을 줄이기 위해, 채널을 거치지 않고 각 패킷을 즉시 전송합니다.
        scope.launch(Dispatchers.IO) {
            try {
                val packet = DatagramPacket(data, data.size, serverAddress, PORT)
                udpSocket?.send(packet)
            } catch (e: Exception) {
                // Log error only if the coroutine scope is still active
                if (isActive) {
                    e.printStackTrace()
                }
            }
        }
    }

    /**
     * Sends a device info packet repeatedly and waits for an ACK from the server.
     */
    private suspend fun performHandshake(deviceInfo: DeviceInfo): Boolean {
        val deviceInfoPacket = createDeviceInfoPacket(deviceInfo)
        repeat(HANDSHAKE_RETRIES) {
            if (isConnectionEstablished) return true
            val packet = DatagramPacket(deviceInfoPacket, deviceInfoPacket.size, serverAddress, PORT)
            udpSocket?.send(packet)
            delay(500)
        }
        return isConnectionEstablished
    }

    /**
     * Starts a coroutine to continuously listen for incoming packets from the server.
     */
    private fun startReceiver() {
        receiverJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(16)
            val packet = DatagramPacket(buffer, buffer.size)

            while (isActive) {
                try {
                    udpSocket?.receive(packet)
                    when (buffer[0]) {
                        PACKET_TYPE_DEVICE_INFO_ACK -> {
                            if (!isConnectionEstablished) {
                                isConnectionEstablished = true
                                lastPongReceivedTime = System.currentTimeMillis()
                            }
                        }
                        PACKET_TYPE_HEARTBEAT_PONG -> {
                            lastPongReceivedTime = System.currentTimeMillis()
                        }
                    }
                } catch (_: SocketTimeoutException) {
                    // Timeout is expected, continue loop.
                } catch (e: Exception) {
                    if (isActive) e.printStackTrace() // Don't log errors if job is cancelled.
                }
            }
        }
    }

    /**
     * Starts a coroutine to periodically send PING packets and check for PONG responses
     * to ensure the connection is still alive.
     */
    private fun startHeartbeat() {
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while(isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (!isConnectionEstablished) continue

                if (System.currentTimeMillis() - lastPongReceivedTime > HEARTBEAT_TIMEOUT_MS) {
                    withContext(Dispatchers.Main) { listener?.onConnectionLost("Connection timed out") }
                    disconnect()
                    break // Exit heartbeat loop.
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

