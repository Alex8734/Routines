package at.resch.routines.core

import android.content.Context
import at.resch.routines.core.action.ContextBrightnessController
import at.resch.routines.core.action.ContextDndController
import at.resch.routines.core.action.ContextIntentLauncher
import at.resch.routines.core.action.ContextNotificationPoster
import at.resch.routines.core.action.ContextVolumeController
import at.resch.routines.core.action.FireAppIntentActionExecutor
import at.resch.routines.core.action.HttpRequestActionExecutor
import at.resch.routines.core.action.ReflectionHotspotController
import at.resch.routines.core.action.SetBrightnessActionExecutor
import at.resch.routines.core.action.SetVolumeProfileActionExecutor
import at.resch.routines.core.action.ShellScriptActionExecutor
import at.resch.routines.core.action.ShowNotificationActionExecutor
import at.resch.routines.core.action.ToggleDndActionExecutor
import at.resch.routines.core.action.ToggleHotspotActionExecutor
import at.resch.routines.core.action.UrlConnectionHttpClient
import at.resch.routines.core.action.WaitActionExecutor
import at.resch.routines.core.cache.SharedPreferencesCapabilityCache
import at.resch.routines.core.shell.RootChecker
import at.resch.routines.core.shell.ShellExecutor
import at.resch.routines.core.trigger.BatteryTriggerSource
import at.resch.routines.core.trigger.BluetoothDeviceTriggerSource
import at.resch.routines.core.trigger.BroadcastBatteryLevelProvider
import at.resch.routines.core.trigger.BroadcastBluetoothConnectionProvider
import at.resch.routines.core.trigger.BroadcastWifiSsidProvider
import at.resch.routines.core.trigger.IntervalTriggerSource
import at.resch.routines.core.trigger.TimeScheduleTriggerSource
import at.resch.routines.core.trigger.WifiSsidTriggerSource
import at.resch.routines.data.AppDatabase
import at.resch.routines.data.MacroRepository
import at.resch.routines.domain.model.Trigger
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Schlanker manueller DI-Container / Composition-Root für die Core-Engine.
 *
 * Stellt **einen** prozessweiten [EventBus] bereit, damit Produzenten
 * ([BootReceiver]) und Konsument ([MacroForegroundService] → [MacroEvaluator])
 * garantiert denselben Bus teilen. Solange kein DI-Framework (Hilt) im Projekt
 * ist, übernimmt dieses Objekt die Verdrahtung. Alle erzeugten Bausteine sind
 * konstruktor-injizierbar und damit in Tests umgehbar.
 */
object EngineContainer {

    @Volatile
    private var _eventBus: EventBus? = null

    /** Prozessweiter, gemeinsam genutzter EventBus. */
    val eventBus: EventBus
        get() = _eventBus ?: synchronized(this) {
            _eventBus ?: EventBus().also { _eventBus = it }
        }

    /** Baut das Repository aus der Room-DB. */
    fun repository(context: Context): MacroRepository =
        MacroRepository(AppDatabase.getInstance(context).macroDao())

    /**
     * Standard-Set an [ActionExecutor]en (Phase 2 Log + Phase 3 System/Root).
     *
     * Benötigt [context] für [FireAppIntentActionExecutor] (PackageManager).
     * Shell-basierte Executoren teilen sich eine [ShellExecutor]-Instanz
     * (zustandslos, Dispatcher = IO).
     */
    fun defaultExecutors(context: Context): List<ActionExecutor> {
        val appContext = context.applicationContext
        val shellExecutor = ShellExecutor()
        // Geteilte Instanzen für alle Compatibility-Contract-Executoren: ein
        // Root-Status-Checker (Cache) und ein Capability-Cache (SharedPreferences).
        val rootChecker = RootChecker()
        val capabilityCache = SharedPreferencesCapabilityCache(
            appContext.getSharedPreferences("capability_cache", Context.MODE_PRIVATE)
        )
        return listOf(
            LogActionExecutor(),
            WaitActionExecutor(),
            ShellScriptActionExecutor(shellExecutor),
            ToggleHotspotActionExecutor(
                rootChecker,
                capabilityCache,
                shellExecutor,
                ReflectionHotspotController(appContext)
            ),
            FireAppIntentActionExecutor(ContextIntentLauncher(appContext)),
            ShowNotificationActionExecutor(ContextNotificationPoster(appContext)),
            HttpRequestActionExecutor(UrlConnectionHttpClient()),
            ToggleDndActionExecutor(ContextDndController(appContext)),
            SetVolumeProfileActionExecutor(ContextVolumeController(appContext)),
            SetBrightnessActionExecutor(ContextBrightnessController(appContext))
        )
    }

    /**
     * Standard-Set an [TriggerSource]s für dauerhaft zu beobachtende Trigger.
     * `OnStartup` ist absichtlich nicht dabei (Einmal-Event via Bus, siehe
     * [TriggerRegistry]).
     */
    fun defaultTriggerSources(context: Context): List<TriggerSource> {
        val appContext = context.applicationContext
        // Eigene Repository-Instanz für die Interval-Quelle: unkritisch, weil
        // AppDatabase.getInstance ein Singleton ist und MacroRepository selbst
        // zustandslos über dem DAO liegt (kein zweiter DB-Handle, kein Cache).
        val intervalConfigs = repository(appContext).observeAll()
            .map { macros ->
                macros.filter { it.enabled }
                    .mapNotNull { it.trigger as? Trigger.Interval }
                    .toSet()
            }
            .distinctUntilChanged()
        return listOf(
            NetworkTriggerSource(NetworkStatusTracker(appContext)),
            BatteryTriggerSource(BroadcastBatteryLevelProvider(appContext)),
            TimeScheduleTriggerSource(),
            IntervalTriggerSource(intervalConfigs),
            WifiSsidTriggerSource(BroadcastWifiSsidProvider(appContext)),
            BluetoothDeviceTriggerSource(BroadcastBluetoothConnectionProvider(appContext))
        )
    }
}
