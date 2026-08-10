package at.resch.routines.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import at.resch.routines.data.MacroRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Persistenter Foreground-Service, der die Engine-Lebenszeit besitzt: er hält den
 * [EventBus], startet [MacroEvaluator] und [TriggerRegistry] in einem eigenen
 * [CoroutineScope] und überlebt App-Schließungen, da das System einen
 * Foreground-Service mit Notification nicht ohne Weiteres killt.
 *
 * Trigger-Quellen (Phase 4) bauen darauf auf. Der Service ist absichtlich dünn:
 * die gesamte Logik liegt in den konstruktor-injizierbaren Bausteinen, die
 * [EngineContainer] verdrahtet → testbar ohne Service-Instanziierung.
 */
class MacroForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var repository: MacroRepository
    private lateinit var evaluator: MacroEvaluator
    private lateinit var triggerRegistry: TriggerRegistry

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "MacroForegroundService onCreate")

        val eventBus = EngineContainer.eventBus
        repository = EngineContainer.repository(applicationContext)
        val engine = MacroEngine(EngineContainer.defaultExecutors(applicationContext))
        evaluator = MacroEvaluator(eventBus, repository, engine)
        triggerRegistry = TriggerRegistry(
            eventBus = eventBus,
            repository = repository,
            sources = EngineContainer.defaultTriggerSources(applicationContext)
        )

        startAsForeground()

        // Evaluator zuerst starten, damit kein (replay=0) Event verpasst wird,
        // bevor gelauscht wird.
        serviceScope.launch { evaluator.start() }
        serviceScope.launch { triggerRegistry.start(serviceScope) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Wenn der BootReceiver uns mit dem Startup-Extra startet, emittiere das
        // Startup-Event — nun ist garantiert, dass der Evaluator bereits lauscht.
        if (intent?.getBooleanExtra(EXTRA_EMIT_STARTUP, false) == true) {
            Log.d(TAG, "Emittiere SystemEvent.Startup")
            serviceScope.launch { EngineContainer.eventBus.emit(SystemEvent.Startup) }
        }
        // Sticky: System soll den Service nach Kill neu starten.
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "MacroForegroundService onDestroy")
        triggerRegistry.stopAll()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground() {
        createNotificationChannel()
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Routines aktiv")
            .setContentText("Automatisierungs-Engine läuft im Hintergrund")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Ab API 34 muss der foregroundServiceType beim Start mitgegeben werden.
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Routines Engine",
            NotificationManager.IMPORTANCE_LOW // leise, keine Töne
        ).apply {
            description = "Persistente Benachrichtigung der Automatisierungs-Engine"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "MacroForegroundService"
        private const val CHANNEL_ID = "routines_engine"
        private const val NOTIFICATION_ID = 1001

        /** Intent-Extra: signalisiert dem Service, ein Startup-Event zu emittieren. */
        const val EXTRA_EMIT_STARTUP = "emit_startup"

        /** Startet den Service; [emitStartup]=true für den Boot-Pfad. */
        fun start(context: Context, emitStartup: Boolean = false) {
            val intent = Intent(context, MacroForegroundService::class.java)
                .putExtra(EXTRA_EMIT_STARTUP, emitStartup)
            context.startForegroundService(intent)
        }
    }
}
