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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.PrintWriter
import java.net.Socket


const val TAG = "kesa"


class MainActivity : AppCompatActivity() {
    private val PORT = 54321 // PC 서버와 동일한 포트
    private var writer: PrintWriter? = null
    private var socket: Socket? = null

    private lateinit var ipAddressInput: EditText
    private lateinit var connectButton: Button
    private lateinit var statusText: TextView
    private lateinit var drawingSurface: FrameLayout

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
    }

    private fun connectToServer(ip: String) {
        // 네트워크 작업은 메인 스레드에서 할 수 없으므로 코루틴 사용
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 소켓을 생성하고 connect 메소드를 사용하여 timeout 설정
                socket = Socket()
                socket?.connect(java.net.InetSocketAddress(ip, PORT), 5000) // 5000ms = 5초 timeout
                writer = PrintWriter(socket!!.getOutputStream(), true)
                withContext(Dispatchers.Main) {
                    statusText.text = "연결됨: $ip"
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    statusText.text = "연결 실패: ${e.message}"
                }
            }
        }
    }

    private fun handleTouchEvent(event: MotionEvent) {
        val toolType = event.getToolType(0)
        if (toolType != MotionEvent.TOOL_TYPE_STYLUS) {
            // 스타일러스가 아닌 경우 무시
            return
        }

        // 연결된 상태일 때만 데이터 전송
        writer?.let {
            val action = when (event.action) {
                MotionEvent.ACTION_DOWN -> "DOWN"
                MotionEvent.ACTION_MOVE -> "MOVE"
                MotionEvent.ACTION_UP -> "UP"
                else -> return
            }
            val x = event.x
            val y = event.y
            val pressure = event.pressure // 필압 정보

            val dataString = "$action:$x,$y,$pressure"

            // 데이터 전송도 네트워크 작업이므로 IO 스레드에서 처리
            lifecycleScope.launch(Dispatchers.IO) {
                it.println(dataString)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 앱 종료 시 소켓 연결 해제
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                writer?.close()
                socket?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}