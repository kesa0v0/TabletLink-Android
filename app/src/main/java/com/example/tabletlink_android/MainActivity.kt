package com.example.tabletlink_android

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.math.cos
import kotlin.math.sin


const val TAG = "kesa"


class MainActivity : AppCompatActivity() {
    private var PORT = 54321 // PC 서버와 동일한 포트
    private var serverAddress: InetAddress? = null
    private var socket: DatagramSocket? = null

    private lateinit var ipAddressInput: EditText
    private lateinit var connectButton: Button
    private lateinit var statusText: TextView
    private lateinit var drawingSurface: FrameLayout


    // 데이터 전송을 위한 채널과 작업 Job
    private val touchDataChannel = Channel<String>(Channel.UNLIMITED) // 버퍼 크기는 상황에 맞게 조절 가능
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
        private const val SEND_INTERVAL_MS = 8L // 125Hz
        private const val HEARTBEAT_INTERVAL_MS = 5000L
        private const val HEARTBEAT_TIMEOUT_MS = 10000L

        // Packet Types
        private const val PACKET_TYPE_DEVICE_INFO_REQ: Byte = 255.toByte()
        private const val PACKET_TYPE_DEVICE_INFO_ACK: Byte = 254.toByte()
        private const val PACKET_TYPE_HEARTBEAT_PING: Byte = 253.toByte()
        private const val PACKET_TYPE_HEARTBEAT_PONG: Byte = 252.toByte()
    }


    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ipAddressInput = findViewById(R.id.ipAddressInput)
        connectButton = findViewById(R.id.connectButton)
        statusText = findViewById(R.id.statusText)
        drawingSurface = findViewById(R.id.drawingSurface)
        drawingSurface.keepScreenOn = true

        connectButton.setOnClickListener {
            val ipAddress = ipAddressInput.text.toString()
            if (ipAddress.isNotEmpty()) {
                statusText.text = "연결 시도 중..."
                connectToServer(ipAddress)
            }
        }

        // 터치 이벤트를 감지하여 서버로 전송
        drawingSurface.setOnTouchListener { _, event ->
            handleTouchEvent(event)
            true // 이벤트를 소비했음을 알림
        }

        drawingSurface.setOnHoverListener { _, event ->
            handleTouchEvent(event)
            true
        }
    }

    private fun connectToServer(ip: String) {
        // 기존 senderJob이 있다면 취소
        disconnect()

        // 네트워크 작업은 메인 스레드에서 할 수 없으므로 코루틴 사용
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                serverAddress = InetAddress.getByName(ip)
                socket = DatagramSocket().apply {
                    soTimeout = 2000 // 수신 대기 시간 2초
                }

                // 1. 수신자 코루틴 시작 (Handshake 응답을 받기 위해 먼저 시작)
                startReceiver()

                // 2. Handshake 시도
                val handshakeSuccess = performHandshake()

                if (handshakeSuccess) {
                    isConnectionEstablished = true
                    lastPongReceivedTime = System.currentTimeMillis()
                    // 3. Handshake 성공 시 송신자 및 Heartbeat 코루틴 시작
                    startSender()
                    startHeartbeat()
                    withContext(Dispatchers.Main) {
                        statusText.text = "연결됨: $ip"
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        statusText.text = "연결 실패 (응답 없음)"
                    }
                    disconnect()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    statusText.text = "연결 오류: ${e.message}"
                }
                disconnect()
            }
        }
    }

    private suspend fun performHandshake(): Boolean {
        // drawingSurface의 크기가 측정될 때까지 잠시 대기
        while (drawingSurface.width == 0 || drawingSurface.height == 0) { delay(100) }

        val deviceInfoPacket = createDeviceInfoPacket(
            drawingSurface.width,
            drawingSurface.height,
            this@MainActivity.display?.refreshRate ?: 60f
        )

        // 최대 5번, 1초 간격으로 Handshake 시도
        repeat(5) {
            val packet =
                DatagramPacket(deviceInfoPacket, deviceInfoPacket.size, serverAddress, PORT)
            socket?.send(packet)
            // ACK 수신 대기 (receiverJob이 처리할 때까지 잠시 기다림)
            delay(1000)
            if (isConnectionEstablished) return true
        }
        return false
    }


    private fun startReceiver() {
        receiverJob = lifecycleScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(16) // 수신할 데이터 버퍼
            val packet = DatagramPacket(buffer, buffer.size)

            while (isActive) {
                try {
                    socket?.receive(packet)
                    val packetType = buffer[0]
                    when (packetType) {
                        PACKET_TYPE_DEVICE_INFO_ACK -> {
                            isConnectionEstablished = true
                        }
                        PACKET_TYPE_HEARTBEAT_PONG -> {
                            lastPongReceivedTime = System.currentTimeMillis()
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    // 타임아웃은 정상적인 상황일 수 있으므로 무시
                } catch (e: Exception) {
                    if (isActive) e.printStackTrace()
                }
            }
        }
    }

    private fun startSender() {
        senderJob = lifecycleScope.launch(Dispatchers.IO) {
            for (data in dataChannel) {
                if (!isActive) break
                val packet = DatagramPacket(data, data.size, serverAddress, PORT)
                socket?.send(packet)
            }
        }
    }

    private fun handleTouchEvent(event: MotionEvent) {
        val toolType = event.getToolType(0)
        if (toolType != MotionEvent.TOOL_TYPE_STYLUS) {
            // 스타일러스가 아닌 경우 무시
            return
        }

        // senderJob이 활성화 상태이고, writer가 준비된 경우에만 채널로 데이터 전송
        if (senderJob?.isActive == true && writer != null) {
            val action = event.actionMasked
            val x = event.x
            val y = event.y
            val pressure = event.pressure // 필압 정보

            // S펜 옆 버튼(배럴 버튼) 감지
            val isBarrelPressed = (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0

            // 기울기(Tilt) 및 방향(Orientation/Azimuth) 값 추출
            val tilt = event.getAxisValue(MotionEvent.AXIS_TILT) // 0 to PI/2 radians
            val orientation = event.getAxisValue(MotionEvent.AXIS_ORIENTATION) // -PI to PI radians

            // Tilt와 Orientation을 사용하여 TiltX, TiltY 계산 (각도로 변환)
            val tiltDegrees = Math.toDegrees(tilt.toDouble()).toInt()
            val tiltX = (tiltDegrees * sin(orientation)).toInt()
            val tiltY = (tiltDegrees * cos(orientation)).toInt()

            val dataString = "$action:$x,$y,$pressure"

            // 채널로 데이터 전송 (send는 suspend 함수이므로 코루틴 내에서 호출)
            // Main 스레드에서 UI 이벤트를 처리하고 빠르게 채널에 넣는 것이 목적이므로
            // 별도의 디스패처 지정 없이 현재 스코프에서 실행.
            lifecycleScope.launch {
                try {
                    touchDataChannel.send(dataString)
                } catch (e: Exception) {
                    // 채널이 닫혔거나 하는 예외 처리
                    e.printStackTrace()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 앱 종료 시 소켓 연결 해제
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. 송신자 코루틴 취소
                senderJob?.cancel()
                // 2. 채널 닫기 (송신자 코루틴의 루프를 정상적으로 종료시킴)
                // 채널을 닫으면 for (data in channel) 루프가 종료됩니다.
                touchDataChannel.close()

                // 3. writer 및 socket 닫기
                writer?.close()
                socket?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun disconnect() {
        TODO("Not yet implemented")
    }
}