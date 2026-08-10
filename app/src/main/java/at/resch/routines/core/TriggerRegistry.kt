package at.resch.routines.core

import android.util.Log
import at.resch.routines.data.MacroRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Verwaltet [TriggerSource]s **dynamisch** abhängig von den aktiven Makros.
 *
 * Eine Quelle wird nur dann gestartet, wenn mindestens ein aktives (`enabled`)
 * Makro ihren `triggerId` verwendet — andernfalls wird sie gestoppt (spart
 * z. B. Netzwerk-Callbacks, wenn kein Makro Mobilfunk-Trigger nutzt).
 *
 * `OnStartup` ist bewusst **keine** [TriggerSource]: das Startup-Event ist ein
 * einmaliges Ereignis, das der [MacroForegroundService]/[BootReceiver] direkt in
 * den [EventBus] emittiert — es gibt nichts dauerhaft zu beobachten.
 *
 * Konstruktor-injiziert (QA: Repository & Sources mockbar).
 */
class TriggerRegistry(
    private val eventBus: EventBus,
    private val repository: MacroRepository,
    /** Alle verfügbaren Quellen, keyed über ihre triggerId. */
    sources: List<TriggerSource>
) {

    private val available: Map<String, TriggerSource> = sources.associateBy { it.triggerId }
    private val running: MutableSet<String> = mutableSetOf()

    /**
     * Beobachtet die aktiven Makros und (de)registriert Quellen entsprechend.
     * Suspendiert bis Scope-Cancel; im Service-Scope zu starten.
     */
    suspend fun start(scope: CoroutineScope) {
        repository.observeAll()
            .map { macros ->
                // Set aller triggerId, die mind. ein aktives Makro benötigt.
                macros.filter { it.enabled }
                    .map { triggerIdOf(it.trigger) }
                    .toSet()
            }
            .distinctUntilChanged()
            .collect { neededTriggerIds -> reconcile(neededTriggerIds, scope) }
    }

    /** Gleicht laufende Quellen mit dem aktuellen Bedarf ab. */
    private fun reconcile(neededTriggerIds: Set<String>, scope: CoroutineScope) {
        // Starte fehlende Quellen.
        neededTriggerIds.forEach { id ->
            val source = available[id] ?: return@forEach
            if (running.add(id)) {
                Log.d(TAG, "Starte TriggerSource '$id'")
                source.start(scope) { event -> eventBus.emit(event) }
            }
        }
        // Stoppe nicht mehr benötigte Quellen.
        (running - neededTriggerIds).forEach { id ->
            available[id]?.let {
                Log.d(TAG, "Stoppe TriggerSource '$id'")
                it.stop()
            }
            running.remove(id)
        }
    }

    /** Stoppt alle Quellen (Service-Teardown). */
    fun stopAll() {
        running.forEach { id -> available[id]?.stop() }
        running.clear()
    }

    /** Mappt einen Domain-[at.resch.routines.domain.model.Trigger] auf seine String-ID. */
    private fun triggerIdOf(trigger: at.resch.routines.domain.model.Trigger): String =
        when (trigger) {
            is at.resch.routines.domain.model.Trigger.OnStartup ->
                BootReceiver.TRIGGER_ID
            is at.resch.routines.domain.model.Trigger.SimCardDataConnected ->
                NetworkStatusTracker.TRIGGER_ID
            is at.resch.routines.domain.model.Trigger.BatteryLevel ->
                at.resch.routines.core.trigger.BatteryTriggerSource.TRIGGER_ID
            is at.resch.routines.domain.model.Trigger.TimeSchedule ->
                at.resch.routines.core.trigger.TimeScheduleTriggerSource.TRIGGER_ID
            is at.resch.routines.domain.model.Trigger.Interval ->
                at.resch.routines.core.trigger.IntervalTriggerSource.TRIGGER_ID
            is at.resch.routines.domain.model.Trigger.WifiSsid ->
                at.resch.routines.core.trigger.WifiSsidTriggerSource.TRIGGER_ID
            is at.resch.routines.domain.model.Trigger.BluetoothDevice ->
                at.resch.routines.core.trigger.BluetoothDeviceTriggerSource.TRIGGER_ID
        }

    companion object {
        private const val TAG = "TriggerRegistry"
    }
}
