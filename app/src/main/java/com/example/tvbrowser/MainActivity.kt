package com.example.remoteclient

import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "ClientMainActivity"
        const val SCREEN_PORT = 9000
        const val CAMERA_PORT = 9002
    }

    private lateinit var imgScreen: ImageView
    private lateinit var imgCamera: ImageView
    private lateinit var txtScreenStatus: TextView
    private lateinit var txtCameraStatus: TextView
    
    private var isScreenListening = false
    private var isCameraListening = false
    
    private var screenServerSocket: ServerSocket? = null
    private var cameraServerSocket: ServerSocket? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val threadPool = Executors.newCachedThreadPool()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate()
        setContentView(R.layout.activity_main)

        imgScreen = findViewById(R.id.imgScreen)
        imgCamera = findViewById(R.id.imgCamera)
        txtScreenStatus = findViewById(R.id.txtScreenStatus)
        txtCameraStatus = findViewById(R.id.txtCameraStatus)

        findViewById<Button>(R.id.btnStartListening).setOnClickListener {
            startListeners()
        }
    }

    private fun startListeners() {
        if (!isScreenListening) {
            isScreenListening = true
            txtScreenStatus.text = "Pantalla: Escuchando puerto $SCREEN_PORT..."
            threadPool.execute { listenForScreenCast() }
        }
        if (!isCameraListening) {
            isCameraListening = true
            txtCameraStatus.text = "Cámara: Escuchando puerto $CAMERA_PORT..."
            threadPool.execute { listenForCamera() }
        }
    }

    private fun listenForScreenCast() {
        try {
            screenServerSocket = ServerSocket(SCREEN_PORT)
            while (isScreenListening) {
                val clientSocket = screenServerSocket?.accept() ?: break
                threadPool.execute { handleScreenStream(clientSocket) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error en ServerSocket de Pantalla: ${e.message}")
        } finally {
            isScreenListening = false
            mainHandler.post { txtScreenStatus.text = "Pantalla: Desconectada" }
        }
    }

    private fun handleScreenStream(socket: Socket) {
        try {
            val dis = DataInputStream(socket.getInputStream())
            while (isScreenListening) {
                val length = dis.readInt()
                if (length <= 0) break
                val data = ByteArray(length)
                dis.readFully(data)

                val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                if (bitmap != null) {
                    mainHandler.post { imgScreen.setImageBitmap(bitmap) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Conexión de pantalla finalizada: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun listenForCamera() {
        try {
            cameraServerSocket = ServerSocket(CAMERA_PORT)
            while (isCameraListening) {
                val clientSocket = cameraServerSocket?.accept() ?: break
                threadPool.execute { handleCameraStream(clientSocket) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error en ServerSocket de Cámara: ${e.message}")
        } finally {
            isCameraListening = false
            mainHandler.post { txtCameraStatus.text = "Cámara: Desconectada" }
        }
    }

    private fun handleCameraStream(socket: Socket) {
        mainHandler.post { txtCameraStatus.text = "Cámara: Transmitiendo en vivo" }
        try {
            val dis = DataInputStream(socket.getInputStream())
            while (isCameraListening) {
                val length = dis.readInt()
                if (length <= 0) break
                val data = ByteArray(length)
                dis.readFully(data)

                val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                if (bitmap != null) {
                    mainHandler.post { imgCamera.setImageBitmap(bitmap) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Conexión de cámara finalizada: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
            mainHandler.post { txtCameraStatus.text = "Cámara: Buscando señal..." }
        }
    }

    override fun onDestroy() {
        isScreenListening = false
        isCameraListening = false
        try { screenServerSocket?.close() } catch (_: Exception) {}
        try { cameraServerSocket?.close() } catch (_: Exception) {}
        threadPool.shutdownNow()
        super.onDestroy()
    }
}
