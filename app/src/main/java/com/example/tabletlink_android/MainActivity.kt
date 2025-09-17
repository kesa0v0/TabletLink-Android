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

        // This listener handles events when the stylus is in contact with the screen.
        drawingSurface.setOnTouchListener { _, event ->
            Log.d(TAG, "onTouch Event -> ${MotionEvent.actionToString(event.actionMasked)} (${event.action})")
            handleTouchEvent(event)
            true
        }

        // This listener handles events when the stylus is NOT in contact (hovering, button presses in air).
        drawingSurface.setOnGenericMotionListener { _, event ->
            Log.d(TAG, "onGenericMotion Event -> ${MotionEvent.actionToString(event.actionMasked)}")
            if (event.isFromSource(InputDevice.SOURCE_STYLUS)) {
                handleTouchEvent(event)
                return@setOnGenericMotionListener true
            }
            false
        }
    }

    private fun handleTouchEvent(event: MotionEvent) {
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return

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

        // --- FIX: Simplify action determination based on physical state ---
        // This logic is more robust than checking for specific, sometimes non-standard, event codes.
        val actionType = when (action) {
            // Pen touches the screen for the first time.
            MotionEvent.ACTION_DOWN -> 0

            // Pen is lifted from the screen.
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> 2

            // Pen is moving while touching the screen.
            // We now treat ANY other touch event as a MOVE, catching the unusual barrel+touch events (like 213).
            MotionEvent.ACTION_MOVE -> 1

            // All other non-contact events (hovering, button presses in air) are treated as hover updates.
            else -> 3
        }

        Log.d(TAG, "Sending -> Action: $actionType, X: ${"%.2f".format(x)}, Y: ${"%.2f".format(y)}, P: ${"%.2f".format(pressure)}, Barrel: $isBarrelPressed, TiltX: $tiltX, TiltY: $tiltY")
        val packetData =
            createPenDataPacket(actionType, x, y, pressure, isBarrelPressed, tiltX, tiltY)
        networkManager.sendPenData(packetData)
        lastSendTime = currentTime
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

