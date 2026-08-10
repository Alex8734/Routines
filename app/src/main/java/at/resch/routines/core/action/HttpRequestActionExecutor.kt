package at.resch.routines.core.action

import android.util.Log
import at.resch.routines.core.ActionExecutor
import at.resch.routines.domain.model.Action
import at.resch.routines.domain.model.ActionResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Ergebnis eines HTTP-Aufrufs: HTTP-Statuscode + Response-Body. */
data class HttpResponse(val statusCode: Int, val body: String)

/**
 * Test-Seam um den eigentlichen HTTP-Aufruf. Erlaubt MockK-Tests ohne Netzwerk.
 * Implementierungen MÜSSEN bereits auf dem richtigen Dispatcher laufen wollen —
 * der Executor schiebt den Aufruf auf den injizierten IO-Dispatcher.
 */
interface HttpClient {
    /**
     * Führt einen HTTP-Request aus. Wirft bei Netzwerk-/IO-Fehlern — der Executor
     * übersetzt das in ein [ActionResult.Failure].
     *
     * [headers] und [timeoutMillis] haben Default-Werte, damit bestehende
     * Implementierungen/Aufrufe ohne diese Parameter weiter funktionieren.
     */
    suspend fun request(
        url: String,
        method: String,
        body: String?,
        headers: Map<String, String> = emptyMap(),
        timeoutMillis: Int = DEFAULT_TIMEOUT_MS
    ): HttpResponse

    companion object {
        /** Default-Timeout (connect + read) in Millisekunden. */
        const val DEFAULT_TIMEOUT_MS = 15_000
    }
}

/**
 * Produktiv-Implementierung über [HttpURLConnection] (keine neue Dependency).
 */
class UrlConnectionHttpClient : HttpClient {

    override suspend fun request(
        url: String,
        method: String,
        body: String?,
        headers: Map<String, String>,
        timeoutMillis: Int
    ): HttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method.uppercase()
            connectTimeout = timeoutMillis
            readTimeout = timeoutMillis
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        try {
            if (!body.isNullOrEmpty()) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return HttpResponse(status, responseBody)
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Executor für `http_request` — feuert einen HTTP-Aufruf ab.
 *
 * Param-Vertrag:
 * - `url`            : String, **erforderlich** — Ziel-URL.
 * - `method`         : String, optional (default `"GET"`) — HTTP-Methode.
 * - `body`           : String, optional — Request-Body (z. B. JSON).
 * - `headers`        : String, optional — Header-Zeilen im Format `Name: Wert`,
 *   getrennt durch `\n`. Name und Wert werden getrimmt. Leerzeilen und Zeilen
 *   ohne `:` werden defensiv ignoriert (kein Crash bei Tippfehlern), ebenso
 *   Zeilen mit leerem Namen. Ein Wert darf `:` enthalten (Split am ersten `:`).
 *   Beispiel: `"Content-Type: application/json\nAuthorization: Bearer abc"`.
 * - `timeoutSeconds` : Int, optional (default `15`) — Connect- **und**
 *   Read-Timeout. Nicht-numerische oder Werte `<= 0` fallen auf den Default
 *   zurück.
 *
 * 2xx → [ActionResult.Success] mit Response-Body. Nicht-2xx oder Exception →
 * [ActionResult.Failure]. Läuft auf dem injizierten IO-Dispatcher. Wirft nie.
 */
class HttpRequestActionExecutor(
    private val client: HttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ActionExecutor {

    override val type: String = "http_request"

    override suspend fun execute(action: Action): ActionResult {
        val url = action.params["url"]
        if (url.isNullOrBlank()) {
            return ActionResult.Failure("http_request: Param 'url' fehlt")
        }
        val method = action.params["method"]?.takeIf { it.isNotBlank() } ?: "GET"
        val body = action.params["body"]?.takeIf { it.isNotEmpty() }
        val headers = parseHeaders(action.params["headers"])
        val timeoutMillis = parseTimeoutMillis(action.params["timeoutSeconds"])

        return try {
            val response = withContext(ioDispatcher) {
                client.request(url, method, body, headers, timeoutMillis)
            }
            if (response.statusCode in 200..299) {
                ActionResult.Success(response.body)
            } else {
                ActionResult.Failure(
                    "http_request: HTTP ${response.statusCode} für $url"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "http_request fehlgeschlagen", e)
            ActionResult.Failure("http_request: Aufruf fehlgeschlagen für $url", e)
        }
    }

    /**
     * Parst den `headers`-Param (`Name: Wert` je Zeile) defensiv in eine Map.
     * Ungültige Zeilen werden still verworfen — ein Tippfehler in der Konfiguration
     * darf den Request nicht zum Absturz bringen.
     */
    private fun parseHeaders(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) return@mapNotNull null
                val name = line.substring(0, separator).trim()
                val value = line.substring(separator + 1).trim()
                if (name.isEmpty()) null else name to value
            }
            .toMap()
    }

    /**
     * Parst `timeoutSeconds` → Millisekunden. Fällt bei fehlendem, nicht-numerischem
     * oder nicht-positivem Wert auf [HttpClient.DEFAULT_TIMEOUT_MS] zurück.
     */
    private fun parseTimeoutMillis(raw: String?): Int {
        val seconds = raw?.trim()?.toIntOrNull() ?: return HttpClient.DEFAULT_TIMEOUT_MS
        if (seconds <= 0) return HttpClient.DEFAULT_TIMEOUT_MS
        // Obergrenze verhindert Int-Overflow bei der Millisekunden-Umrechnung.
        return seconds.coerceAtMost(MAX_TIMEOUT_SECONDS) * 1000
    }

    companion object {
        private const val TAG = "HttpRequestAction"

        /** Obergrenze für `timeoutSeconds` (10 Minuten). */
        const val MAX_TIMEOUT_SECONDS = 600
    }
}
