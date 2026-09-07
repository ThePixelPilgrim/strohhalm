package de.nereide.strohhalm.work

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * The exemption that takes the app out of App Standby buckets and lets its
 * jobs run under Doze. Without it a rarely opened app's periodic sync can
 * be held for hours after a reboot, until the next launch bumps the bucket.
 */
object BatteryOptimisation {

    fun isExempt(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** The system dialog asking for the exemption; needs `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. */
    fun requestIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        )
}
