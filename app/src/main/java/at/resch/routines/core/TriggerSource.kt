package at.resch.routines.core

import kotlinx.coroutines.CoroutineScope

/**
 * Vertrag für eine Quelle, die System-Zustände beobachtet und [SystemEvent]s in
 * den [EventBus] einspeist (z. B. Netzwerk-Status, später Battery, Geofence …).
 *
 * Erweiterungs-Seam für Phase 4: jeder neue Trigger-Typ liefert eine eigene
 * [TriggerSource]. Die [TriggerRegistry] startet/stoppt sie dynamisch, je nachdem
 * ob aktuell aktive Makros den zugehörigen Trigger nutzen.
 */
interface TriggerSource {

    /**
     * Der Trigger-Typ (= `@SerialName` des zugehörigen `Trigger`, z. B.
     * `"sim_card_data_connected"`), für den diese Quelle Events erzeugt.
     */
    val triggerId: String

    /**
     * Startet die Beobachtung im gegebenen [scope] und emittiert Events über
     * [emit]. Idempotent gegenüber Mehrfach-Start sollte der Aufrufer (Registry)
     * sicherstellen. Wird der [scope] gecancelt, endet die Quelle.
     */
    fun start(scope: CoroutineScope, emit: suspend (SystemEvent) -> Unit)

    /** Stoppt die Beobachtung und gibt Ressourcen frei. */
    fun stop()
}
