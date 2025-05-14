package com.example.tabletlink_android

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.thibaultbee.srtdroid.core.Srt
import io.github.thibaultbee.srtdroid.core.models.SrtSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException
import kotlin.concurrent.thread


const val TAG = "kesa"


class MainActivity : AppCompatActivity() {
    val server = Network(InetAddress.getByName("10.0.2.2"), 12346, 12345)
    private lateinit var surfaceView: SurfaceView
    private lateinit var codec: MediaCodec
    private lateinit var surface: Surface


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        Srt.startUp()
        surfaceView = findViewById<SurfaceView>(R.id.surfaceView)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surface = holder.surface
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                // SurfaceView 크기 변경 시 처리
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // SurfaceView 파괴 시 처리
            }
        })

        findViewById<Button>(R.id.Discover).setOnClickListener {
            server.discoverServer()
        }

        findViewById<Button>(R.id.SendPen).setOnClickListener {
            server.testSend()
        }

        val s = findViewById<SwitchCompat>(R.id.switch1)
        s.setOnClickListener {
            if (s.isChecked) {
                try {
                    startStreaming()
                }
                catch (e: Exception) {
                    Log.e(TAG, "Error starting streaming: ${e.message}")
                    s.isChecked = false
                }
            } else {
                server.stopListen()
            }
        }

        findViewById<TextView>(R.id.ipView).text = server.getLocalIpAddress()
    }


    private fun startStreaming() {
        thread {
            try {
                val serverSocket = SrtSocket()
                serverSocket.connect(InetSocketAddress("127.0.0.1", 12345))
                val inputStream = serverSocket.getInputStream()

                // 미리 설정된 H.264 스트림 포맷 (적절히 조정)
                val format = MediaFormat.createVideoFormat("video/avc", 1280, 720)
                codec = MediaCodec.createDecoderByType("video/avc")
                codec.configure(format, surface, null, 0)
                codec.start()

                val buffer = ByteArray(4096)
                var read: Int

                while (true) {
                    read = inputStream.read(buffer)
                    if (read <= 0) break

                    val inIndex = codec.dequeueInputBuffer(10000)
                    if (inIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inIndex)!!
                        inputBuffer.clear()
                        inputBuffer.put(buffer, 0, read)
                        codec.queueInputBuffer(inIndex, 0, read, System.nanoTime() / 1000, 0)
                    }

                    val outIndex = codec.dequeueOutputBuffer(MediaCodec.BufferInfo(), 10000)
                    if (outIndex >= 0) {
                        codec.releaseOutputBuffer(outIndex, true)
                    }
                }

                inputStream.close()
                serverSocket.close()

            }
            catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    override fun onDestroy() {
        server.stopListen()
        super.onDestroy()

        Srt.cleanUp()
        if (::codec.isInitialized) {
            codec.stop()
            codec.release()
        }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event == null) return false

        val toolType = event.getToolType(0)

        if (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER) {
            Log.d(TAG, "onTouchEvent: toolType: $toolType")

            val x = event.getX(0)
            val y = event.getY(0)
            val pressure = event.getPressure(0)
            val orientation = event.getOrientation(0)
            val tilt = event.getAxisValue(MotionEvent.AXIS_TILT, 0)
            val distance = event.getAxisValue(MotionEvent.AXIS_DISTANCE, 0)
            val timestamp = System.currentTimeMillis()

            val penData = PenData(x, y, pressure, orientation, tilt, distance, timestamp)
            val sendData = server.sendPacketToByte(Network.PacketType.PEN_INPUT, penData)

            CoroutineScope(Dispatchers.IO).launch {
                val packet = DatagramPacket(
                    sendData,
                    sendData.size,
                    server.serverAddress,
                    server.sendPort
                )
                server.sendSocket?.send(packet)
                Log.d(TAG, "send packet")
            }
        }

        return super.onTouchEvent(event)
    }
}