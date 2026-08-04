package com.radarrower

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class RadarApp : Application() {

    companion object {
        const val CHANNEL_SERVICE = "radar_service"
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                getString(R.string.notif_channel_service),
                NotificationManager.IMPORTANCE_LOW, // bez dźwięku — alerty gra AlertPlayer
            ).apply {
                description = getString(R.string.notif_channel_service_desc)
                setShowBadge(false)
            }
        )
    }
}
