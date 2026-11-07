package com.example.advancedtunnel.vpn

import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket

object TunPacketForwarder {
    private const val TAG = "TunPacketForwarder"
    private const val BUFFER_SIZE = 32767

    /**
     * Bridges the virtual TUN interface file descriptor with the secure TLS/Proxy socket.
     * Reads Layer 3 IP packets from OS TUN, parses/processes them, and streams them 
     * through the secure tunnel socket, while proxying responses back to TUN.
     */
    fun startBridging(
        vpnInput: FileInputStream,
        vpnOutput: FileOutputStream,
        secureSocket: SSLSocket,
        isRunning: AtomicBoolean,
        onBytesTransferred: (rx: Long, tx: Long) -> Unit
    ) {
        val serverIn = secureSocket.inputStream
        val serverOut = secureSocket.outputStream

        var totalRx = 0L
        var totalTx = 0L

        // Downstream Thread: Server -> OS TUN
        val downstreamThread = Thread({
            val buffer = ByteArray(BUFFER_SIZE)
            try {
                while (isRunning.get()) {
                    val n = serverIn.read(buffer)
                    if (n > 0) {
                        vpnOutput.write(buffer, 0, n)
                        vpnOutput.flush()
                        totalRx += n
                        onBytesTransferred(totalRx, totalTx)
                    } else if (n < 0) {
                        Log.i(TAG, "Server closed downstream tunnel stream.")
                        break
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Downstream bridging error: ${e.message}")
                }
            }
        }, "TunDownstreamBridge")

        downstreamThread.start()

        // Upstream Loop: OS TUN -> Server
        val upBuffer = ByteArray(BUFFER_SIZE)
        try {
            while (isRunning.get()) {
                val length = vpnInput.read(upBuffer)
                if (length > 0) {
                    // Optional: Inspect IP packet header version / destination if needed
                    // For standard full-tunnel encapsulation, stream raw IP payload bytes directly:
                    serverOut.write(upBuffer, 0, length)
                    serverOut.flush()
                    totalTx += length
                    onBytesTransferred(totalRx, totalTx)
                } else if (length < 0) {
                    Log.i(TAG, "OS TUN interface closed upstream.")
                    break
                }
            }
        } catch (e: Exception) {
            if (isRunning.get()) {
                Log.e(TAG, "Upstream bridging error: ${e.message}")
            }
        } finally {
            try {
                downstreamThread.interrupt()
            } catch (_: Exception) {}
        }
    }
}
