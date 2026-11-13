package com.example.advancedtunnel.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.example.advancedtunnel.MainActivity
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class TunnelVpnService : VpnService() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false

    companion object {
        const val ACTION_START = "com.example.advancedtunnel.START_VPN"
        const val ACTION_STOP = "com.example.advancedtunnel.STOP_VPN"
        const val NOTIFICATION_CHANNEL_ID = "tunnel_console_channel"
        const val NOTIFICATION_ID = 1984
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopVpn()
            else -> startVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning) return
        isRunning = true

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Tunnel Console Active", "Secure tunnel connected"))

        serviceScope.launch {
            try {
                establishVpnTunnel()
            } catch (e: Exception) {
                stopVpn()
            }
        }
    }

    private fun establishVpnTunnel() {
        val builder = Builder()
            .addAddress("10.66.66.2", 24)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .setMtu(1280)
            .setSession("TunnelConsoleSession")

        vpnInterface = builder.establish()

        vpnInterface?.let { pfd ->
            val inputStream = FileInputStream(pfd.fileDescriptor)
            val outputStream = FileOutputStream(pfd.fileDescriptor)

            serviceScope.launch {
                runBlockingLoop(inputStream, outputStream)
            }
        }
    }

    private suspend fun runBlockingLoop(input: FileInputStream, output: FileOutputStream) = withContext(Dispatchers.IO) {
        val buffer = ByteArray(32768)
        val serverHost = "2.29.8.194"
        val serverPort = 443
        val sniHost = "static.194.8.29.2.clients.your-server.de"

        try {
            val rawSocket = Socket()
            protect(rawSocket)
            rawSocket.connect(InetSocketAddress(serverHost, serverPort), 10000)

            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val sslSocket = sslFactory.createSocket(rawSocket, serverHost, serverPort, true) as SSLSocket

            sslSocket.sslParameters.apply {
                serverNames = listOf(SNIHostName(sniHost))
            }
            sslSocket.startHandshake()

            val socketInput = sslSocket.inputStream
            val socketOutput = sslSocket.outputStream

            val readerJob = launch {
                try {
                    while (isActive && isRunning) {
                        val bytesRead = socketInput.read(buffer)
                        if (bytesRead > 0) {
                            output.write(buffer, 0, bytesRead)
                        } else if (bytesRead == -1) break
                    }
                } catch (_: Exception) {}
            }

            val writerJob = launch {
                try {
                    while (isActive && isRunning) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead > 0) {
                            socketOutput.write(buffer, 0, bytesRead)
                            socketOutput.flush()
                        } else if (bytesRead == -1) break
                    }
                } catch (_: Exception) {}
            }

            // KeepAlive ping every 20 seconds
            val keepAliveJob = launch {
                while (isActive && isRunning) {
                    delay(20000)
                    try {
                        socketOutput.write(byteArrayOf(0x00))
                        socketOutput.flush()
                    } catch (_: Exception) {
                        break
                    }
                }
            }

            joinAll(readerJob, writerJob, keepAliveJob)
            sslSocket.close()
        } catch (e: Exception) {
            // Handle reconnection or error state
        }
    }

    private fun stopVpn() {
        isRunning = false
        serviceJob.cancelChildren()
        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Tunnel Console VPN Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Maintains persistent secure foreground tunnel connection"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
        serviceJob.cancel()
    }
}
