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
    // Throttle move events to ~125Hz (1000ms / 8ms) to avoid network flooding.
    private val sendIntervalMs = 8L

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
            val ipAddress = ipAddressInput.text.toString().trim()
            if (ipAddress.isNotEmpty()) {
                statusText.text = "Connecting..."
                // Use post to ensure the view has been laid out and has dimensions.
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

        drawingSurface.setOnTouchListener { _, event -> handleStylusEvent(event) }
        drawingSurface.setOnHoverListener { _, event -> handleStylusEvent(event) }
    }

    /**
     * Handles both touch and hover events, filtering for stylus input.
     * @return True if the event was handled, false otherwise.
     */
    private fun handleStylusEvent(event: MotionEvent): Boolean {
        // Only process events from a stylus tool.
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) {
            return false
        }

        val currentTime = System.currentTimeMillis()
        val action = event.actionMasked

        // Throttle move/hover events to prevent flooding the network.
        if ((action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_HOVER_MOVE) &&
            (currentTime - lastSendTime < sendIntervalMs)) {
            return true // Event consumed, but not sent.
        }

        val penAction = when (action) {
            MotionEvent.ACTION_DOWN -> PenAction.DOWN
            MotionEvent.ACTION_MOVE -> PenAction.MOVE
            MotionEvent.ACTION_UP -> PenAction.UP
            MotionEvent.ACTION_HOVER_MOVE -> PenAction.HOVER
            else -> null // Ignore other actions like POINTER_UP/DOWN, HOVER_ENTER/EXIT etc.
        }

        penAction?.let {
            val isBarrelPressed = (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0

            // Tilt is reported in radians from the vertical axis.
            val tilt = event.getAxisValue(MotionEvent.AXIS_TILT)
            // Orientation is the clockwise angle of the pen from the vertical axis.
            val orientation = event.getAxisValue(MotionEvent.AXIS_ORIENTATION)

            // Decompose tilt and orientation into X and Y components.
            // Tilt is 0 when perpendicular, PI/2 when parallel. We convert to degrees from -90 to 90.
            val tiltDegrees = Math.toDegrees(tilt.toDouble())
            val tiltX = (tiltDegrees * sin(orientation)).toInt()
            val tiltY = (tiltDegrees * cos(orientation)).toInt()

            val packetData = createPenDataPacket(
                action = it.id,
                x = event.x,
                y = event.y,
                pressure = event.pressure,
                isBarrelPressed = isBarrelPressed,
                tiltX = tiltX,
                tiltY = tiltY
            )
            networkManager.sendPenData(packetData)
            lastSendTime = currentTime
        }

        return true
    }

    /**
     * Creates the 17-byte data packet to be sent over UDP.
     * The structure must match the C# receiver's parsing logic.
     */
    private fun createPenDataPacket(action: Int, x: Float, y: Float, pressure: Float, isBarrelPressed: Boolean, tiltX: Int, tiltY: Int): ByteArray {
        val buffer = ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN)

        var actionAndFlags = action.toByte()
        if (isBarrelPressed) {
            actionAndFlags = (actionAndFlags.toInt() or (1 shl 4)).toByte() // Set the 5th bit
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

    // region NetworkManager.NetworkListener Implementation
    override fun onConnectionSuccess(ip: String) {
        statusText.text = "Connected to: $ip"
    }

    override fun onConnectionFailed(message: String) {
        statusText.text = "Connection Failed: $message"
    }

    override fun onConnectionLost(message: String) {
        statusText.text = "Connection Lost: $message"
    }
    // endregion

    private enum class PenAction(val id: Int) {
        DOWN(0),
        MOVE(1),
        UP(2),
        HOVER(3)
    }
}
