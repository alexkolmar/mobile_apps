package com.example

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat

class LocalBlockVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val action = intent.action
            if (action == ACTION_STOP) {
                stopVpn()
                // Broadcast that the service was stopped manually
                sendServiceStatusBroadcast(false)
                return START_NOT_STICKY
            }
        }
        
        startVpn(intent)
        return START_STICKY
    }

    private fun startVpn(intent: Intent?) {
        stopVpn()

        val blockedApps = intent?.getStringArrayListExtra(EXTRA_BLOCKED_PACKAGES) ?: emptyList<String>()
        if (blockedApps.isEmpty()) {
            Log.d("LocalVpnService", "No apps selected to block. Stopping.")
            stopSelf()
            return
        }

        // Setup notification actions
        val stopIntent = Intent(this, LocalBlockVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 
            0, 
            stopIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Selective Flight Mode Active")
            .setContentText("Blocking connection for ${blockedApps.size} apps")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(mainPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Service", stopPendingIntent)
            .setOngoing(true)
            .build()

        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e("LocalVpnService", "Failed to start as foreground service", e)
        }
        
        try {
            val builder = Builder()
                .setSession("FlightModeSimulator")
                .setMtu(1500)
                .addAddress("10.8.0.1", 24)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)

            var addedCount = 0
            for (packageName in blockedApps) {
                try {
                    builder.addAllowedApplication(packageName)
                    addedCount++
                } catch (e: Exception) {
                    Log.e("LocalVpnService", "Failed to router-bind app: $packageName", e)
                }
            }

            if (addedCount == 0) {
                Log.d("LocalVpnService", "No apps were matching for binding. Stopping.")
                stopVpn()
                return
            }

            vpnInterface = builder.establish()
            sendServiceStatusBroadcast(true)
            Log.d("LocalVpnService", "VPN established. Successfully intercepting $addedCount apps.")
        } catch (e: Exception) {
            Log.e("LocalVpnService", "Error establishing VPN socket", e)
            stopVpn()
        }
    }

    private fun stopVpn() {
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e("LocalVpnService", "Failed to close interface", e)
        }
        vpnInterface = null
        stopForeground(true)
        stopSelf()
        sendServiceStatusBroadcast(false)
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun sendServiceStatusBroadcast(isRunning: Boolean) {
        val broadcastIntent = Intent(ACTION_STATUS_CHANGED).apply {
            putExtra(EXTRA_IS_RUNNING, isRunning)
        }
        sendBroadcast(broadcastIntent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "App Flight Mode Active Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Indicates when specific apps are selected to run offline via local block VPN"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "vpn_flight_mode_channel"
        const val NOTIFICATION_ID = 2026
        const val ACTION_STOP = "com.example.ACTION_STOP"
        const val ACTION_STATUS_CHANGED = "com.example.ACTION_STATUS_CHANGED"
        const val EXTRA_IS_RUNNING = "com.example.EXTRA_IS_RUNNING"
        const val EXTRA_BLOCKED_PACKAGES = "com.example.EXTRA_BLOCKED_PACKAGES"
    }
}
