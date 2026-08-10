package at.resch.routines.core

import at.resch.routines.domain.model.Trigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * [TriggerSource] für `"sim_card_data_connected"`. Adaptiert den vorhandenen
 * [NetworkStatusTracker]-Flow auf den [EventBus]: bei jedem Übergang nach
 * "verbunden" (`true`) wird [SystemEvent.CellularDataConnected] emittiert.
 *
 * Der zugrundeliegende Flow ist `distinctUntilChanged`, daher feuert das Event
 * nur bei echten Verbindungs-*Übergängen*, nicht repetitiv.
 */
class NetworkTriggerSource(
    private val tracker: NetworkStatusTracker
) : TriggerSource {

    override val triggerId: String = NetworkStatusTracker.TRIGGER_ID

    private var job: Job? = null

    override fun start(scope: CoroutineScope, emit: suspend (SystemEvent) -> Unit) {
        if (job?.isActive == true) return // idempotent
        job = scope.launch {
            tracker.isCellularConnected
                .filter { connected -> connected }
                .collect { emit(SystemEvent.CellularDataConnected) }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        /** Trigger-Diskriminator, mit dem diese Quelle korreliert. */
        val MATCHING_TRIGGER: Trigger = Trigger.SimCardDataConnected
    }
}
