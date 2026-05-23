package com.example.remoteclient

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private val SERVER_IP_FALLBACK = "127.0.0.1"
    private var discoveredServerIp: String? = null

    private lateinit var imgScreen: ImageView
    private lateinit var imgCamera: ImageView
    private lateinit var txtCameraStatus: TextView

    private val isRunning = AtomicBoolean(true)

    private var cameraServerSocket: ServerSocket? = null
    private var screenServerSocket: ServerSocket? = null
    private val CAMERA_PORT = 9002
    private val SCREEN_PORT = 9000
    private val NOTIFY_PORT = 9003

    private val uiHandler = Handler(Looper.getMainLooper())

    private var previousScreenBitmap: Bitmap? = null
    private var previousCameraBitmap: Bitmap? = null

    private var isCameraAvailable = true
    private var isCameraStreaming = false
    private var currentCameraFacing = 0 // 0=trasera, 1=frontal

    private var screenListenerThread: Thread? = null
    private var cameraListenerThread: Thread? = null

    // --- mDNS ---
    private lateinit var nsdManager: NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val SERVICE_TYPE = "_screenstream._tcp."

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        imgScreen = findViewById(R.id.viewScreen)
        imgCamera = findViewById(R.id.viewCamera)
        txtCameraStatus = findViewById(R.id.txtCameraStatus)

        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager

        setupButtons()
        startScreenListener()
        startCameraListener()
        startNotificationListener()
        startMdnsDiscovery()
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.btnTglScreen).setOnClickListener {
            enviarComando("START_SCREEN")
        }

        findViewById<Button>(R.id.btnTglBack).setOnClickListener {
            if (isCameraAvailable) {
                currentCameraFacing = 0
                enviarComando("START_BACK")
            } else {
                Toast.makeText(this, "Cámara no disponible", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnTglFront).setOnClickListener {
            if (isCameraAvailable) {
                currentCameraFacing = 1
                enviarComando("START_FRONT")
            } else {
                Toast.makeText(this, "Cámara no disponible", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnTglAudio).setOnClickListener {
            Toast.makeText(this, "Audio no implementado aún", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnStopCamera).setOnClickListener {
            enviarComando("STOP_CAMERA")
            stopCameraStreaming()
        }

        findViewById<Button>(R.id.btnStopAll).setOnClickListener {
            enviarComando("STOP")
            stopAllStreams()
        }
    }

    private fun stopCameraStreaming() {
        isCameraStreaming = false
        updateCameraStatusText()
        runOnUiThread {
            previousCameraBitmap?.recycle()
            previousCameraBitmap = null
            imgCamera.setImageBitmap(null)
        }
    }

    private fun stopAllStreams() {
        isCameraStreaming = false
        updateCameraStatusText()
        runOnUiThread {
            previousScreenBitmap?.recycle()
            previousScreenBitmap = null
            imgScreen.setImageBitmap(null)
            previousCameraBitmap?.recycle()
            previousCameraBitmap = null
            imgCamera.setImageBitmap(null)
        }
    }

    // ==================== mDNS ====================
    private fun startMdnsDiscovery() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("ScreenClient::mDNS").apply {
            setReferenceCounted(true)
            acquire()
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                runOnUiThread { Toast.makeText(this@MainActivity, "Buscando servidor...", Toast.LENGTH_SHORT).show() }
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType == SERVICE_TYPE) {
                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                            discoveredServerIp = resolvedInfo.host.hostAddress
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "Servidor encontrado: $discoveredServerIp", Toast.LENGTH_LONG).show()
                            }
                            nsdManager.stopServiceDiscovery(discoveryListener)
                            multicastLock?.release()
                        }
                    })
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Fallo en descubrimiento. Usando IP local.", Toast.LENGTH_LONG).show()
                    discoveredServerIp = SERVER_IP_FALLBACK
                }
                multicastLock?.release()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        discoveryListener = listener
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)

        // Timeout
        uiHandler.postDelayed({
            if (discoveredServerIp == null) {
                nsdManager.stopServiceDiscovery(discoveryListener)
                discoveredServerIp = SERVER_IP_FALLBACK
                Toast.makeText(this, "No se encontró servidor. Usando IP local.", Toast.LENGTH_LONG).show()
                multicastLock?.release()
            }
        }, 6000)
    }

    private fun getServerIp(): String = discoveredServerIp ?: SERVER_IP_FALLBACK

    // ==================== Notification Listener ====================
    private fun startNotificationListener() {
        thread(start = true) {
            var serverSocket: ServerSocket? = null
            while (isRunning.get()) {
                try {
                    if (serverSocket == null || serverSocket.isClosed) {
                        serverSocket = ServerSocket(NOTIFY_PORT)
                    }

                    val socket = serverSocket.accept()
                    val command = socket.getInputStream().bufferedReader().readLine()
                    socket.close()

                    uiHandler.post {
                        when {
                            command?.startsWith("CAMERA_AVAILABLE:") == true -> {
                                val facing = command.substringAfter(":").toIntOrNull() ?: 0
                                currentCameraFacing = facing
                                isCameraAvailable = true
                                updateCameraStatusText()
                                Toast.makeText(this@MainActivity, "Cámara ${getCameraName()} disponible", Toast.LENGTH_SHORT).show()
                            }
                            command?.startsWith("CAMERA_UNAVAILABLE:") == true -> {
                                val facing = command.substringAfter(":").toIntOrNull() ?: 0
                                currentCameraFacing = facing
                                isCameraAvailable = false
                                isCameraStreaming = false
                                updateCameraStatusText()
                                stopCameraStreaming()
                                Toast.makeText(this@MainActivity, "Cámara ${getCameraName()} no disponible", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (!isRunning.get()) break
                    Thread.sleep(2000)
                }
            }
            serverSocket?.close()
        }
    }

    private fun getCameraName(): String = if (currentCameraFacing == 1) "frontal" else "trasera"

    private fun updateCameraStatusText() {
        val status = when {
            !isCameraAvailable -> "Cámara ${getCameraName()} no disponible"
            isCameraStreaming -> "Transmitiendo cámara ${getCameraName()}"
            else -> "Cámara ${getCameraName()} detenida"
        }
        val color = when {
            !isCameraAvailable -> android.R.color.holo_red_dark
            isCameraStreaming -> android.R.color.holo_green_dark
            else -> android.R.color.darker_gray
        }
        txtCameraStatus.text = status
        txtCameraStatus.setTextColor(ContextCompat.getColor(this, color))
    }

    // ==================== Screen Listener ====================
    private fun startScreenListener() {
        screenListenerThread = thread(start = true) {
            while (isRunning.get()) {
                try {
                    screenServerSocket = ServerSocket(SCREEN_PORT)
                    val socket = screenServerSocket!!.accept().apply {
                        tcpNoDelay = true
                        receiveBufferSize = 512 * 1024
                    }

                    val dis = DataInputStream(socket.getInputStream())
                    val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }

                    while (isRunning.get()) {
                        val length = dis.readInt()
                        if (length <= 0 || length > 2_000_000) continue

                        val data = ByteArray(length)
                        dis.readFully(data)

                        val bmp = BitmapFactory.decodeByteArray(data, 0, length, options) ?: continue

                        uiHandler.post {
                            previousScreenBitmap?.recycle()
                            previousScreenBitmap = bmp
                            imgScreen.setImageBitmap(bmp)
                        }
                    }
                } catch (e: Exception) {
                    // Reconexión
                } finally {
                    screenServerSocket?.close()
                }
                if (isRunning.get()) Thread.sleep(4000)
            }
        }
    }

    // ==================== Camera Listener (Optimizado para Redmi) ====================
    private fun startCameraListener() {
        cameraListenerThread = thread(start = true) {
            while (isRunning.get()) {
                try {
                    cameraServerSocket = ServerSocket(CAMERA_PORT)
                    val socket = cameraServerSocket!!.accept().apply {
                        tcpNoDelay = true
                        receiveBufferSize = 512 * 1024
                    }

                    val dis = DataInputStream(socket.getInputStream())
                    val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }

                    while (isRunning.get()) {
                        val length = dis.readInt()
                        if (length <= 0 || length > 2_000_000) continue

                        val data = ByteArray(length)
                        dis.readFully(data)

                        var bmp = BitmapFactory.decodeByteArray(data, 0, length, options) ?: continue

                        // Rotación optimizada para Redmi Note 14 Pro+
                        val matrix = Matrix().apply {
                            postRotate(if (currentCameraFacing == 1) 270f else 90f)
                            if (currentCameraFacing == 1) postScale(-1f, 1f) // Espejo frontal
                        }

                        val transformed = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                        if (transformed != bmp) bmp.recycle()

                        if (!isCameraStreaming) {
                            isCameraStreaming = true
                            uiHandler.post { updateCameraStatusText() }
                        }

                        uiHandler.post {
                            previousCameraBitmap?.recycle()
                            previousCameraBitmap = transformed
                            imgCamera.setImageBitmap(transformed)
                        }
                    }
                } catch (e: Exception) {
                    // Error silencioso (reconexión)
                } finally {
                    isCameraStreaming = false
                    uiHandler.post { updateCameraStatusText() }
                }

                if (isRunning.get()) Thread.sleep(3000)
            }
        }
    }

    private fun enviarComando(cmd: String) {
        thread {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(getServerIp(), 9001), 1500)
                    socket.tcpNoDelay = true
                    socket.getOutputStream().write((cmd + "\n").toByteArray())
                    socket.getOutputStream().flush()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Error enviando comando: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroy() {
        isRunning.set(false)
        screenListenerThread?.interrupt()
        cameraListenerThread?.interrupt()
        try {
            screenServerSocket?.close()
            cameraServerSocket?.close()
        } catch (e: Exception) {}
        previousScreenBitmap?.recycle()
        previousCameraBitmap?.recycle()
        nsdManager.stopServiceDiscovery(discoveryListener)
        multicastLock?.release()
        super.onDestroy()
    }
}
