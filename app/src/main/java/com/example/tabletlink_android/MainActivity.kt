package com.example.tabletlink_android

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
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
    private val TAG = "TabletLink"


    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ipAddressInput = findViewById(R.id.ipAddressInput)
        connectButton = findViewById(R.id.connectButton)
        statusText = findViewById(R.id.statusText)
        drawingSurface = findViewById(R.id.drawingSurface)
        drawingSurface.keepScreenOn = true

        networkManager = NetworkManager(lifecycleScope)

        connectButton.setOnClickListener {
            val ipAddress = ipAddressInput.text.toString()
            if (ipAddress.isNotEmpty()) {
                statusText.text = "Connecting..."
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

        // --- FIX: Revised listener setup to better capture events before system interception ---
        // 터치(화면 접촉) 이벤트를 처리합니다.
        drawingSurface.setOnTouchListener { _, event ->
            // Log every touch event that the listener receives
            Log.d(TAG, "onTouch Event -> ${MotionEvent.actionToString(event.actionMasked)}")
            handleTouchEvent(event)
            true // Return true to consume the event and prevent further processing
        }

        // 호버링, 스타일러스 버튼 등 터치가 아닌 모든 모션 이벤트를 처리합니다.
        // This is the correct listener for hover and stylus button events.
        drawingSurface.setOnGenericMotionListener { _, event ->
            // Log every generic motion event
            Log.d(TAG, "onGenericMotion Event -> ${MotionEvent.actionToString(event.actionMasked)}")
            // Ensure the event is from a stylus before processing
            if (event.isFromSource(InputDevice.SOURCE_STYLUS)) {
                handleTouchEvent(event)
                return@setOnGenericMotionListener true // Consume the event
            }
            false
        }
    }

    private fun handleTouchEvent(event: MotionEvent) {
        val toolType = event.getToolType(0)
        if (toolType != MotionEvent.TOOL_TYPE_STYLUS) {
            return
        }
        val currentTime = System.currentTimeMillis()
        val action = event.actionMasked

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

        // --- FIX: Expanded to handle more event types just in case ---
        val actionType = when (action) {
            MotionEvent.ACTION_DOWN -> 0         // Pen touches screen
            MotionEvent.ACTION_MOVE -> 1         // Pen moves on screen
            MotionEvent.ACTION_UP -> 2           // Pen lifts from screen
            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_HOVER_MOVE,
            MotionEvent.ACTION_HOVER_EXIT -> 3   // All hover events
            MotionEvent.ACTION_BUTTON_PRESS,
            MotionEvent.ACTION_BUTTON_RELEASE -> 3 // Treat button presses as hover updates
            else -> -1
        }

        if (actionType != -1) {
            Log.d(TAG, "Sending -> Action: $actionType, X: ${"%.2f".format(x)}, Y: ${"%.2f".format(y)}, P: ${"%.2f".format(pressure)}, Barrel: $isBarrelPressed, TiltX: $tiltX, TiltY: $tiltY")
            val packetData =
                createPenDataPacket(actionType, x, y, pressure, isBarrelPressed, tiltX, tiltY)
            networkManager.sendPenData(packetData)
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

    // NetworkManager.NetworkListener implementation
    override fun onConnectionSuccess(ip: String) {
        statusText.text = "Connected: $ip"
    }

    override fun onConnectionFailed(message: String) {
        statusText.text = "Connection Failed: $message"
    }

    override fun onConnectionLost(message: String) {
        statusText.text = "Connection Lost: $message"
    }
}

