package at.resch.routines.domain.model

/**
 * Ergebnis einer einzelnen Action-Ausführung. Erlaubt Fehlerisolation pro Action
 * (eine fehlgeschlagene Action stoppt nicht zwingend das ganze Makro) und
 * strukturierte Fehlerrückgabe für Root-/Permission-Fallbacks (Phase 3).
 */
sealed interface ActionResult {

    data class Success(val output: String? = null) : ActionResult

    data class Failure(
        val reason: String,
        val cause: Throwable? = null
    ) : ActionResult
}
