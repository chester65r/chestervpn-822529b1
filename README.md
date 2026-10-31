# chesterVPN

Project Dependencies (app/build.gradle.kts)

Kotlin

plugins {

    id("com.android.application")

    id("org.jetbrains.kotlin.android")

}



android {

    namespace = "com.example.advancedtunnel"

    compileSdk = 34



    defaultConfig {

        applicationId = "com.example.advancedtunnel"

        minSdk = 26

        targetSdk = 34

        versionCode = 1

        versionName = "1.0.0"

    }



    buildFeatures {

        viewBinding = true

    }

}



dependencies {

    implementation("androidx.core:core-ktx:1.12.0")

    implementation("androidx.appcompat:appcompat:1.6.1")

    implementation("com.google.android.material:material:1.11.0")

    implementation("androidx.constraintlayout:

constraintlayout:2.1.4")

}



File 2: Application Manifest Permissions (AndroidManifest.xml)

Xml

File 3: The Application User Interface Layout (activity_main.xml)

Xml 

<?xml version="1.0" encoding="utf-8"?>

<manifest xmlns:android="http://android.com"

    package="com.example.advancedtunnel">



    <uses-permission android:name="android.permission.INTERNET" />

    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />



    <application

        android:allowBackup="false"

        android:icon="@mipmap/ic_launcher"

        android:label="Advanced Tunnel"

        android:theme="@style/Theme.AppCompat.Light.NoActionBar">

        

        <activity

            android:name=".MainActivity"

            android:exported="true">

            <intent-filter>

                <action android:name="android.intent.action.MAIN" />

                <category android:name="android.intent.category.LAUNCHER" />

            </intent-filter>

        </activity>



        <service

            android:name=".AdvancedVpnService"

            android:permission="android.permission.BIND_VPN_SERVICE"

            android:exported="false">

            <intent-filter>

                <action android:name="android.net.VpnService" />

            </intent-filter>

        </service>



    </application>



File 4: Custom Configuration Storage Models (TunnelData.kt)

Kotlin

package com.example.advancedtunnel



import android.content.Context

import android.content.SharedPreferences

import java.io.Serializable



data class TunnelConfig(

    val serverIp: String,

    val serverPort: Int,

    val sniHost: String,

    val customPayload: String

) : Serializable



object PayloadEngine {

    fun parse(rawPayload: String, sniHost: String): String {

        if (rawPayload.isBlank()) {

            return "GET / HTTP/1.1\r\nHost: $sniHost\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n"

        }

        return rawPayload

            .replace("[host]", sniHost)

            .replace("[crlf]", "\r\n")

            .replace("\\r\\n", "\r\n") + "\r\n\r\n"

    }

}



class ConfigStorage(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("TunnelPrefs", Context.MODE_PRIVATE)



    fun saveConfig(config: TunnelConfig) {

        prefs.edit().apply {

            putString("SERVER_IP", config.serverIp)

            putInt("SERVER_PORT", config.serverPort)

            putString("SNI_HOST", config.sniHost)

            putString("PAYLOAD", config.customPayload)

            apply()

        }

    }



    fun loadActiveConfig(): TunnelConfig {

        return TunnelConfig(

            serverIp = prefs.getString("SERVER_IP", "") ?: "",

            serverPort = prefs.getInt("SERVER_PORT", 443),

            sniHost = prefs.getString("SNI_HOST", "") ?: "",

            customPayload = prefs.getSt

ring("PAYLOAD", "") ?: ""

        )

    }



File 5: The System VPN Lifecycle Interceptor (AdvancedVpnService.kt)

Kotlin 

package com.example.advancedtunnel



import android.content.Intent

import android.net.VpnService

import android.os.ParcelFileDescriptor

import android.util.Log

import java.io.FileInputStream

import java.io.FileOutputStream

import java.net.InetSocketAddress

import java.net.Socket

import javax.net.ssl.SNIHostName

import javax.net.ssl.SSLParameters

import javax.net.ssl.SSLSocket

import javax.net.ssl.SSLSocketFactory



class AdvancedVpnService : VpnService(), Runnable {



    private var vpnInterface: ParcelFileDescriptor? = null

    private var tunnelThread: Thread? = null

    @Volatile private var isRunning = false



    private var serverIp = ""

    private var serverPort = 443

    private var sniHost = ""

    private var rawPayload = ""



    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        if (intent?.action == "ACTION_STOP") {

            stopTunnel()

            return START_NOT_STICKY

        }



        serverIp = intent?.getStringExtra("SERVER_IP") ?: ""

        serverPort = intent?.getIntExtra("SERVER_PORT", 443) ?: 443

        sniHost = intent?.getStringExtra("SNI_HOST") ?: ""

        rawPayload = intent?.getStringExtra("PAYLOAD") ?: ""



        if (serverIp.isBlank() || sniHost.isBlank()) {

            stopSelf()

            return START_NOT_STICKY

        }



        isRunning = true

        tunnelThread = Thread(this, "AdvancedTunnelThread").apply { start() }

        return START_STICKY

    }



    override fun run() {

        var rawSocket: Socket? = null

        var secureSocket: SSLSocket? = null



        try {

            // Establish system virtual device wrapper

            vpnInterface = Builder()

                .addAddress("10.0.0.2", 24)

                .addRoute("0.0.0.0", 0)

                .addDnsServer("8.8.8.8")

                .setSession("AdvancedTunnelEngine")

                .establish()



            rawSocket = Socket()

            rawSocket.connect(InetSocketAddress(serverIp, serverPort), 10000)



            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory

            secureSocket = factory.createSocket(rawSocket, serverIp, serverPort, true) as SSLSocket

            val params = SSLParameters()

            params.serverNames = listOf(SNIHostName(sniHost))

            secureSocket.sslParameters = params

            secureSocket.startHandshake()



            val formattedPayload = PayloadEngine.parse(rawPayload, sniHost)

            secureSocket.outputStream.write(formattedPayload.toByteArray(Charsets.UTF_8))

            secureSocket.outputStream.flush()



            val vpnInput = FileInputStream(vpnInterface!!.fileDescriptor)

            val vpnOutput = FileOutputStream(vpnInterface!!.fileDescriptor)

            val serverInputStream = secureSocket.inputStream

            val serverOutputStream = secureSocket.outputStream



            // Handle incoming server downlink channel

            val downstreamThread = Thread {

                val downstreamBuffer = ByteArray(32768)

                try {

                    while (isRunning) {

                        val readFromServer = serverInputStream.read(downstreamBuffer)

                        if (readFromServer > 0) {

                            vpnOutput.write(downstreamBuffer, 0, readFromServer)

                        }

                    }

                } catch (e: Exception) {

                    Log.e("VpnService", "Downstream channel closed.")

                }

            }

            downstreamThread.start()



            // Handle device local uplink loop

            val buffer = ByteArray(32768)

            while (isRunning) {

                val bytesRead = vpnInput.read(buffer)

                if (bytesRead > 0) {

                    serverOutputStream.write(buffer, 0, bytesRead)

                    serverOutputStream.flush()

                }

                Thread.sleep(2)

            }



        } catch (e: Exception) {

            Log.e("VpnService", "Tunnel layer runtime error occurred.", e)

        } finally {

            try { secureSocket?.close() } catch (_: Exception) {}

            try { rawSocket?.close() } catch (_: Exception) {}

            stopTunnel()

        }

    }



    private synchronized fun stopTunnel() {

        if (!isRunning) return

        isRunning = false

        try {

            vpnInterface?.close()

        } catch (e: Exception) {

            Log.e("VpnService", "Error during release handler execution.", e)

        }

        vpnInter

face = null

        stopSelf()

    }



    override fun onDestroy() {

        stopTunnel()

        super.onDestroy()

    }



File 6: Main Entry Validation Controller (MainActivity.kt)

kotlin

package com.example.advancedtunnel



import android.content.Intent

import android.net.VpnService

import android.os.Bundle

import android.widget.Toast

import androidx.appcompat.app.AppCompatActivity

import com.example.advancedtunnel.databinding.ActivityMainBinding



class MainActivity : AppCompatActivity() {



    private lateinit var binding: ActivityMainBinding

    private lateinit var storage: ConfigStorage

    private var isVpnActive = false

    private val VPN_REQUEST_CODE = 42



    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState: Bundle?)

        binding = ActivityMainBinding.inflate(layoutInflater)

        setContentView(binding.root)

        

        storage = ConfigStorage(this)

        loadSavedProfile()



        binding.btnToggleVpn.setOnClickListener {

            if (isVpnActive) {

                stopVpnService()

            } else {

                if (validateInputs()) {

                    saveCurrentProfile()

                    startVpnAuthorization()

                }

            }

        }

    }



    private fun validateInputs(): Boolean {

        val ip = binding.etServerIp.text.toString().trim()

        val port = binding.etServerPort.text.toString().trim()

        val sni = binding.etSniHost.text.toString().trim()



        if (ip.isEmpty()) {

            binding.etServerIp.error = "Server IP cannot be empty"

            return false

        }

        if (port.isEmpty() || port.toIntOrNull() == null || port.toInt() !in 1..65535) {

            binding.etServerPort.error = "Enter a valid port (1-65535)"

            return false

        }

        if (sni.isEmpty()) {

            binding.etSniHost.error = "SNI target host required"

            return false

        }

        return true

    }



    private fun loadSavedProfile() {

        val config = storage.loadActiveConfig()

        binding.etServerIp.setText(config.serverIp)

        binding.etServerPort.setText(config.serverPort.toString())

        binding.etSniHost.setText(config.sniHost)

        binding.etPayload.setText(config.customPayload)

    }



    private fun saveCurrentProfile() {

        val currentConfig = TunnelConfig(

            serverIp = binding.etServerIp.text.toString().trim(),

            serverPort = binding.etServerPort.text.toString().trim().toInt(),

            sniHost = binding.etSniHost.text.toString().trim(),

            customPayload = binding.etPayload.text.toString()

        )

        storage.saveConfig(currentConfig)

    }



    private fun startVpnAuthorization() {

        val intent = VpnService.prepare(this)

        if (intent != null) {

            startActivityForResult(intent, VPN_REQUEST_CODE)

        } else {

            onActivityResult(VPN_REQUEST_CODE, RESULT_OK, null)

        }

    }



    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {

        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {

            startVpnService()

        } else {

            Toast.makeText(this, "VPN permission required", Toast.LENGTH_LONG).show()

        }

    }



    private fun startVpnService() {

        val config = storage.loadActiveConfig()

        val serviceIntent = Intent(this, AdvancedVpnService::class.java).apply {

            putExtra("SERVER_IP", config.serverIp)

            putExtra("SERVER_PORT", config.serverPort)

            putExtra("SNI_HOST", config.sniHost)

            putExtra("PAYLOAD", config.customPayload)

        }

        startService(serviceIntent)

        binding.btnToggleVpn.text = "STOP TUNNEL"

        isVpnActive = true

    }



    private fun stopVpnService() {

        val serviceIntent = Intent(this, AdvancedVpnService::class.java).apply {

            action = "ACTION_STOP"

        }

        start

Service(serviceIntent)

        binding.btnToggleVpn.text = "START TUNNEL"

        isVpnActive = false

    }



Production Compilation Steps

Create Android Studio Project: Initialize a fresh Kotlin-supported application using the "Empty Views Activity" base template. Ensure the primary namespace configuration exactly matches com.example.advancedtunnel.

Populate Files: Overwrite your local project files with the complete production code layers provided above.

Connect Your Remote VPS Server: Set up a remote proxy server matching the destination host configuration parameters entered into the user interface components to begin traffic routing

}

}

}



</manifest>

This project was built with [Lovable](https://lovable.dev).

**Live app**: https://chestervpn.lovable.app

## Build with Lovable

Continue developing this project in the [Lovable editor](https://lovable.dev/projects/316563bc-095f-489d-bb30-eabf79a4e7cb).

- **Ship faster**: describe what you want to build and Lovable handles the code.
- **Stay in sync**: every change made in Lovable is committed straight to this repository.
- **Full ownership**: this code is yours. Push to `main` on GitHub and your changes sync back into Lovable, ready for your next prompt.

## Development

Prefer working locally? You need Node.js and npm — [install with nvm](https://github.com/nvm-sh/nvm#installing-and-updating).

```sh
git clone <this-repository-url>
cd <repository-name>
npm i
npm run dev
```
