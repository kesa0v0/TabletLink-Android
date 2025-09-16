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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin


class MainActivity : AppCompatActivity(), NetworkManager.NetworkListener {
    private lateinit var ipAddressInput: EditText
    private lateinit var connectButton: Button
    private lateinit var statusText: TextView
    private lateinit var drawingSurface: FrameLayout


    private lateinit var networkManager: NetworkManager


    private var lastSendTime: Long = 0
    private val SEND_INTERVAL_MS = 8L // 125Hz


    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ipAddressInput = findViewById(R.id.ipAddressInput)
        connectButton = findViewById(R.id.connectButton)
        statusText = findViewById(R.id.statusText)
        drawingSurface = findViewById(R.id.drawingSurface)
        drawingSurface.keepScreenOn = true

        // lifecycleScope을 사용하여 NetworkManager 생성
        networkManager = NetworkManager(lifecycleScope)

        connectButton.setOnClickListener {
            val ipAddress = ipAddressInput.text.toString()
            if (ipAddress.isNotEmpty()) {
                statusText.text = "연결 시도 중..."
                drawingSurface.post {
                    val deviceInfo = NetworkManager.DeviceInfo(
                        drawingSurface.width,
                        drawingSurface.height,
                        this.display?.refreshRate ?: 60f
                    )
                    networkManager.connect(ipAddress, deviceInfo, this)
                }
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

    private fun handleTouchEvent(event: MotionEvent) {
        val toolType = event.getToolType(0)
        if (toolType != MotionEvent.TOOL_TYPE_STYLUS) {
            // 스타일러스가 아닌 경우 무시
            return
        }
        val currentTime = System.currentTimeMillis()
        val action = event.actionMasked

        // MOVE/HOVER 이벤트 쓰로틀링
        if ((action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_HOVER_MOVE) &&
            (currentTime - lastSendTime < SEND_INTERVAL_MS)
        ) {
            return
        }

        val x = event.x
        val y = event.y
        val pressure = event.pressure
        val isBarrelPressed = (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        val tilt = Math.toDegrees(event.getAxisValue(MotionEvent.AXIS_TILT).toDouble()).toInt()
        val orientation = event.getAxisValue(MotionEvent.AXIS_ORIENTATION)
        val tiltX = (tilt * sin(orientation)).toInt()
        val tiltY = (tilt * cos(orientation)).toInt()

        val actionType = when (action) {
            MotionEvent.ACTION_DOWN -> 0
            MotionEvent.ACTION_MOVE -> 1
            MotionEvent.ACTION_UP -> 2
            MotionEvent.ACTION_HOVER_MOVE -> 3
            else -> -1
        }

        if (actionType != -1) {
            val packetData =
                createPenDataPacket(actionType, x, y, pressure, isBarrelPressed, tiltX, tiltY)
            networkManager.sendPenData(packetData) // NetworkManager를 통해 데이터 전송
            lastSendTime = currentTime
        }
    }

    private fun createPenDataPacket(
        action: Int,
        x: Float,
        y: Float,
        pressure: Float,
        isBarrelPressed: Boolean,
        tiltX: Int,
        tiltY: Int
    ): ByteArray {
        val buffer = ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN)
        var actionAndFlags = action.toByte()
        if (isBarrelPressed) {
            actionAndFlags = (actionAndFlags.toInt() or (1 shl 4)).toByte()
        }
        buffer.put(actionAndFlags)
        buffer.putFloat(x)
        buffer.putFloat(y)
        buffer.putFloat(pressure)
        buffer.putShort(tiltX.toShort())
        buffer.putShort(tiltY.toShort())
        return buffer.array()
    }


    override fun onDestroy() {
        super.onDestroy()
        networkManager.disconnect()
    }

    // NetworkManager.NetworkListener 구현
    override fun onConnectionSuccess(ip: String) {
        statusText.text = "연결됨: $ip"
    }

    override fun onConnectionFailed(message: String) {
        statusText.text = "연결 실패: $message"
    }

    override fun onConnectionLost(message: String) {
        statusText.text = "연결 끊김: $message"
    }
}