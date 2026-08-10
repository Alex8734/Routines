package at.resch.routines.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Empfängt `BOOT_COMPLETED` und startet den [MacroForegroundService] mit dem
 * Startup-Signal. Der Service emittiert daraufhin [SystemEvent.Startup] in den
 * [EventBus] — erst *nachdem* der [MacroEvaluator] lauscht, sodass kein Event
 * (replay=0) verloren geht.
 *
 * Der Receiver führt selbst KEINE Makros aus: lange Arbeit gehört nicht in
 * onReceive (10s-Limit). Er delegiert ausschließlich an den Service.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.d(TAG, "BOOT_COMPLETED empfangen → starte Service + Startup-Event")
        MacroForegroundService.start(context.applicationContext, emitStartup = true)
    }

    companion object {
        private const val TAG = "BootReceiver"
        const val TRIGGER_ID = "on_startup"
    }
}
