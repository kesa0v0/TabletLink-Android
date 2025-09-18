package com.example.tabletlink_android

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.LayoutInflater
import android.view.MotionEvent
// import android.view.View // Not strictly needed for these specific changes
import android.view.ViewGroup
// import android.view.Window // Not directly used, WindowCompat is preferred
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat // Added
import androidx.core.view.WindowInsetsCompat // Added
import androidx.core.view.WindowInsetsControllerCompat // Added
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin


class MainActivity : AppCompatActivity(), NetworkManager.NetworkListener {
    private lateinit var fabSettings: FloatingActionButton
    private lateinit var drawingSurface: FrameLayout
    private lateinit var screenView: ImageView
    private lateinit var networkManager: NetworkManager

    // Views within the settings dialog
    private var dialogIpAddressInput: EditText? = null
    private var dialogConnectButton: Button? = null
    private var dialogStatusText: TextView? = null
    private var currentSettingsDialog: AlertDialog? = null

    private var lastSendTime: Long = 0
    private val SEND_INTERVAL_MS = 8L // 125Hz
    private val TAG = "TabletLink"

    // For FAB dragging
    private var dX: Float = 0f
    private var dY: Float = 0f
    private var lastAction: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f
    private val CLICK_DRAG_TOLERANCE = 10f // Pixels

    // To persist dialog state
    private var lastAttemptedIp: String? = null
    private var currentStatusMessage: String = "연결되지 않음"


    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable full screen
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContentView(R.layout.activity_main)

        fabSettings = findViewById(R.id.fab_settings)
        drawingSurface = findViewById(R.id.drawingSurface)
        screenView = findViewById(R.id.screenView)
        drawingSurface.keepScreenOn = true

        networkManager = NetworkManager(lifecycleScope)
        currentStatusMessage = "연결되지 않음" // Initialize status

        setupDraggableFab()

        drawingSurface.setOnTouchListener { _, event ->
            Log.d(TAG, "onTouch Event -> ${MotionEvent.actionToString(event.actionMasked)} (${event.action})")
            handleTouchEvent(event)
            true
        }

        drawingSurface.setOnGenericMotionListener { _, event ->
            Log.d(TAG, "onGenericMotion Event -> ${MotionEvent.actionToString(event.actionMasked)}")
            if (event.isFromSource(InputDevice.SOURCE_STYLUS)) {
                handleTouchEvent(event)
                return@setOnGenericMotionListener true
            }
            false
        }
    }

    override fun onScreenFrameReceived(bitmap: Bitmap) {
        screenView.setImageBitmap(bitmap)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDraggableFab() {
        fabSettings.setOnTouchListener { view, event ->
            val parentView = view.parent as ViewGroup
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dX = view.x - event.rawX
                    dY = view.y - event.rawY
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    lastAction = MotionEvent.ACTION_DOWN
                }
                MotionEvent.ACTION_MOVE -> {
                    var newX = event.rawX + dX
                    var newY = event.rawY + dY
                    newX = newX.coerceIn(0f, (parentView.width - view.width).toFloat())
                    newY = newY.coerceIn(0f, (parentView.height - view.height).toFloat())
                    view.x = newX
                    view.y = newY
                    lastAction = MotionEvent.ACTION_MOVE
                }
                MotionEvent.ACTION_UP -> {
                    val deltaX = abs(event.rawX - initialTouchX)
                    val deltaY = abs(event.rawY - initialTouchY)
                    if (lastAction == MotionEvent.ACTION_DOWN || (deltaX < CLICK_DRAG_TOLERANCE && deltaY < CLICK_DRAG_TOLERANCE)) {
                        showSettingsDialog()
                    }
                }
                else -> return@setOnTouchListener false
            }
            true
        }
    }

    private fun showSettingsDialog() {
        if (currentSettingsDialog?.isShowing == true) {
            return
        }

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        dialogIpAddressInput = dialogView.findViewById(R.id.ipAddressInput)
        dialogConnectButton = dialogView.findViewById(R.id.connectButton)
        dialogStatusText = dialogView.findViewById(R.id.statusText)

        dialogIpAddressInput?.setText(lastAttemptedIp)
        dialogStatusText?.text = currentStatusMessage

        dialogConnectButton?.setOnClickListener {
            val ipAddress = dialogIpAddressInput?.text.toString()
            if (ipAddress.isNotEmpty()) {
                lastAttemptedIp = ipAddress
                currentStatusMessage = "Connecting..."
                dialogStatusText?.text = currentStatusMessage

                drawingSurface.post {
                    if (isDestroyed || isFinishing) return@post
                    val width = drawingSurface.width
                    val height = drawingSurface.height
                    if (width == 0 || height == 0) {
                        Log.e(TAG, "Drawing surface has not been measured yet.")
                        currentStatusMessage = "오류: 화면 크기를 가져올 수 없습니다."
                        dialogStatusText?.text = currentStatusMessage
                        return@post
                    }
                    val deviceInfo = NetworkManager.DeviceInfo(width, height, this.display?.refreshRate ?: 60f)
                    networkManager.connect(ipAddress, deviceInfo, this@MainActivity)
                }
            } else {
                dialogIpAddressInput?.error = "IP 주소를 입력하세요."
            }
        }

        currentSettingsDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setTitle("연결 설정")
            .setPositiveButton("닫기") { dialog, _ -> dialog.dismiss() }
            .setNeutralButton("종료") { _, _ -> // Added Exit button
                finishAffinity() // Close the app
            }
            .setOnDismissListener {
                dialogIpAddressInput = null
                dialogConnectButton = null
                dialogStatusText = null
                currentSettingsDialog = null
            }
            .create()
        currentSettingsDialog?.show()
    }

    private fun handleTouchEvent(event: MotionEvent) {
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return
        val currentTime = System.currentTimeMillis()
        if ((event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_HOVER_MOVE) && (currentTime - lastSendTime < SEND_INTERVAL_MS)) {
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
        val actionType = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> 0
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> 2
            MotionEvent.ACTION_MOVE -> 1
            else -> 3
        }
        Log.d(TAG, "Sending -> Action: $actionType, X: ${"%.2f".format(x)}, Y: ${"%.2f".format(y)}, P: ${"%.2f".format(pressure)}, Barrel: $isBarrelPressed, TiltX: $tiltX, TiltY: $tiltY")
        val packetData = createPenDataPacket(actionType, x, y, pressure, isBarrelPressed, tiltX, tiltY)
        networkManager.sendPenData(packetData)
        lastSendTime = currentTime
    }

    private fun createPenDataPacket(action: Int, x: Float, y: Float, pressure: Float, isBarrelPressed: Boolean, tiltX: Int, tiltY: Int): ByteArray {
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
        currentSettingsDialog?.dismiss()
        currentSettingsDialog = null
    }

    override fun onConnectionSuccess(ip: String) {
        lastAttemptedIp = ip
        currentStatusMessage = "Connected: $ip"
        if (currentSettingsDialog?.isShowing == true) {
            dialogStatusText?.text = currentStatusMessage
            currentSettingsDialog?.dismiss()
        } else {
            Snackbar.make(fabSettings, currentStatusMessage, Snackbar.LENGTH_LONG)
                .setAnchorView(fabSettings)
                .setAction("Dismiss") { /* No action needed */ }
                .show()
        }
    }

    override fun onConnectionFailed(message: String) {
        currentStatusMessage = "Connection Failed: $message"
        if (currentSettingsDialog?.isShowing == true) {
            dialogStatusText?.text = currentStatusMessage
        } else {
            Snackbar.make(fabSettings, currentStatusMessage, Snackbar.LENGTH_LONG)
                .setAnchorView(fabSettings)
                .setAction("Settings") { showSettingsDialog() }
                .show()
        }
    }

    override fun onConnectionLost(message: String) {
        currentStatusMessage = "Connection Lost: $message"
        if (currentSettingsDialog?.isShowing == true) {
            dialogStatusText?.text = currentStatusMessage
        } else {
            Snackbar.make(fabSettings, currentStatusMessage, Snackbar.LENGTH_LONG)
                .setAnchorView(fabSettings)
                .setAction("Settings") { showSettingsDialog() }
                .show()
        }
    }
}
