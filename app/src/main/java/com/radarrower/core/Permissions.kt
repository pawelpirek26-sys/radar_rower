package com.radarrower.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Jedno miejsce prawdy o uprawnieniach runtime — używane przez onboarding
 * i sekcję diagnostyczną w Ustawieniach.
 */
object Permissions {

    /** Lista uprawnień do dopraszania (zależna od wersji Androida). */
    fun required(): Array<String> {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        return perms.toTypedArray()
    }

    /** „Urządzenia w pobliżu": skan + łączenie BLE (na 11- lokalizacja). */
    fun hasNearby(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 31) {
            granted(context, Manifest.permission.BLUETOOTH_SCAN) &&
                granted(context, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** Powiadomienia (od Androida 13 wymagają zgody). */
    fun hasNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            granted(context, Manifest.permission.POST_NOTIFICATIONS)

    fun hasAll(context: Context): Boolean =
        required().all { granted(context, it) }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
}
