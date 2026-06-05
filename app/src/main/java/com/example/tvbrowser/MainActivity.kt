package com.example.remoteclient

import android.graphics.BitmapFactory
import android.os.Bundle // Asegura el import correcto de Android OS
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

    companion object {
        private const val TAG = "ClientMainActivity"
        private const val SCREEN_PORT = 9000
        private const val CAMERA_PORT = 9002
    }

    // Declaramos las variables como nulleables o usando inicialización tardía correcta
    private var imgScreen: ImageView? = null
    private var imgCamera: ImageView? = null
    private var txtScreenStatus: TextView? = null
    private var txtCameraStatus: TextView? = null
    
    private var isScreenListening = false
    private var isCameraListening = false
    
    private var screenServerSocket: ServerSocket? = null
    private var cameraServerSocket: ServerSocket? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val threadPool = Executors.newCachedThreadPool()

    // Corregimos la firma estricta de onCreate con el Bundle de android.os
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Intentamos mapear dinámicamente buscando los IDs por el nombre que tengan en tu XML
        // para evitar que falle la compilación si cambian de mayúsculas/minúsculas
        val layoutId = resources.getIdentifier("activity_main", "layout", packageName)
        if (layoutId != 0) {
            setContentView(layoutId)
        }

        // Mapeo seguro buscando por texto identificador en el layout XML
        imgScreen = findViewById(resources.getIdentifier("imgScreen", "id", packageName))
        imgCamera = findViewById(resources.getIdentifier("imgCamera", "id", packageName))
        txtScreenStatus = findViewById(resources.getIdentifier("txtScreenStatus", "id", packageName))
        txtCameraStatus = findViewById(resources.getIdentifier("txtCameraStatus", "id", packageName))

        val btnStartListening = findViewById<Button>(resources.getIdentifier("btnStartListening", "id", packageName))
        btnStartListening?.setOnClickListener {
            startListeners()
        }
    }

    private fun startListeners() {
        if (!isScreenListening) {
            isScreenListening = true
            txtScreenStatus?.text = "Pantalla: Escuchando puerto $SCREEN_PORT..."
            threadPool.execute { listenForScreenCast() }
        }
        if (!isCameraListening) {
            isCameraListening = true
            txtCameraStatus?.text = "Cámara: Escuchando puerto $CAMERA_PORT..."
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
            mainHandler.post { txtScreenStatus?.text = "Pantalla: Desconectada" }
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
                    mainHandler.post { imgScreen?.setImageBitmap(bitmap) }
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
            mainHandler.post { txtCameraStatus?.text = "Cámara: Desconectada" }
        }
    }

    private fun handleCameraStream(socket: Socket) {
        mainHandler.post { txtCameraStatus?.text = "Cámara: Transmitiendo en vivo" }
        try {
            val dis = DataInputStream(socket.getInputStream())
            while (isCameraListening) {
                val length = dis.readInt()
                if (length <= 0) break
                val data = ByteArray(length)
                dis.readFully(data)

                val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                if (bitmap != null) {
                    mainHandler.post { imgCamera?.setImageBitmap(bitmap) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Conexión de cámara finalizada: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
            mainHandler.post { txtCameraStatus?.text = "Cámara: Buscando señal..." }
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
