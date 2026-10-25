package com.dynamicdroid.app

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat

/**
 * Exists only to own an accessibility overlay window.
 *
 * Normal "display over other apps" windows sit *below* the status bar, so taps at the very top
 * of the screen go to the system and pull down the notification shade. Accessibility overlays
 * sit *above* the status bar, so the island gets its own touches there. It reads no screen content.
 */
class IslandAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        onChanged?.invoke()
        // Bring the island up if it isn't running yet.
        runCatching { ContextCompat.startForegroundService(this, Intent(this, IslandOverlayService::class.java)) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        release()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun release() {
        if (instance === this) {
            instance = null
            onChanged?.invoke()
        }
    }

    companion object {
        @Volatile var instance: IslandAccessibilityService? = null
            private set

        /** Set by the overlay service so it can move its window when this connects/disconnects. */
        var onChanged: (() -> Unit)? = null

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val me = ComponentName(context, IslandAccessibilityService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
