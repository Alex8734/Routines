package at.resch.routines.core

import at.resch.routines.domain.model.Action
import at.resch.routines.domain.model.ActionResult

/**
 * Vertrag für die Ausführung eines konkreten Action-Typs.
 *
 * Erweiterungs-Seam für Phase 3/4: jeder neue Action-Typ
 * (`execute_shell_script`, `toggle_hotspot`, `fire_app_intent`, …) wird als
 * eigener [ActionExecutor] implementiert und unter seinem [type] in der
 * [MacroEngine]-Registry registriert. Die Engine selbst bleibt unverändert.
 */
interface ActionExecutor {

    /** Der Action-Typ (= [Action.type]), den dieser Executor bedient. */
    val type: String

    /**
     * Führt die [action] aus. Muss Fehler in [ActionResult.Failure] übersetzen
     * statt zu werfen (graceful degradation, z. B. Permission-Denial/kein Root).
     * Die Engine fängt zwar auch geworfene Exceptions ab, aber strukturierte
     * Failures sind vorzuziehen.
     */
    suspend fun execute(action: Action): ActionResult
}
