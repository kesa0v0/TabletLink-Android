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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.PrintWriter
import java.net.Socket
import kotlin.math.cos
import kotlin.math.sin


const val TAG = "kesa"


class MainActivity : AppCompatActivity() {
    private val PORT = 54321 // PC 서버와 동일한 포트
    private var writer: PrintWriter? = null
    private var socket: Socket? = null

    private lateinit var ipAddressInput: EditText
    private lateinit var connectButton: Button
    private lateinit var statusText: TextView
    private lateinit var drawingSurface: FrameLayout


    // 데이터 전송을 위한 채널과 작업 Job
    private val touchDataChannel = Channel<String>(Channel.UNLIMITED) // 버퍼 크기는 상황에 맞게 조절 가능
    private var senderJob: Job? = null


    private var lastSendTime: Long = 0
    private val SEND_INTERVAL_MS = 8 // ms, 약 125Hz로 전송률 제한


    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ipAddressInput = findViewById(R.id.ipAddressInput)
        connectButton = findViewById(R.id.connectButton)
        statusText = findViewById(R.id.statusText)
        drawingSurface = findViewById(R.id.drawingSurface)

        connectButton.setOnClickListener {
            val ipAddress = ipAddressInput.text.toString()
            if (ipAddress.isNotEmpty()) {
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
        senderJob?.cancel()
        // 채널도 이전 연결에서 사용되었을 수 있으므로, 새로 만들거나 비우는 것이 안전할 수 있으나,
        // 여기서는 connectToServer가 호출될 때마다 새 Job을 만들므로 이전 채널 내용을 소비하게 됨.
        // 만약 이전 채널에 데이터가 남아있으면 새 연결에서 전송될 수 있으니 주의.
        // 좀 더 견고하게 하려면 채널을 닫고 새로 만들거나 clear 하는 로직이 필요할 수 있음.

        // 네트워크 작업은 메인 스레드에서 할 수 없으므로 코루틴 사용
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 소켓을 생성하고 connect 메소드를 사용하여 timeout 설정
                socket = Socket()
                socket?.connect(java.net.InetSocketAddress(ip, PORT), 5000) // 5000ms = 5초 timeout
                writer = PrintWriter(socket!!.getOutputStream(), true)

                // DrawingSurface의 크기 보내기
                val drawWidth = drawingSurface.width
                val drawHeight = drawingSurface.height
                val refreshRate = this@MainActivity.display.refreshRate.toInt()

                val deviceInfo = "DEVICEINFO:$drawWidth,$drawHeight,$refreshRate"
                writer?.println(deviceInfo)

                // 송신자 코루틴 시작
                senderJob = launch { // 부모 코루틴(Dispatchers.IO)의 컨텍스트를 상속
                    try {
                        for (dataString in touchDataChannel) { // 채널에서 데이터를 계속 읽음
                            if (!isActive || socket?.isClosed == true || writer == null) break // 코루틴/소켓 비활성 시 중단
                            writer?.println(dataString)
                        }
                    } catch (e: Exception) {
                        // 채널이 닫히거나 전송 중 오류 발생 시
                        e.printStackTrace()
                    }
                }

                withContext(Dispatchers.Main) {
                    statusText.text = "연결됨: $ip"
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    statusText.text = "연결 실패: ${e.message}"
                }
                // 연결 실패 시 writer, socket 정리
                writer?.close()
                socket?.close()
                writer = null
                socket = null
                senderJob?.cancel() // senderJob도 확실히 정리
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
}