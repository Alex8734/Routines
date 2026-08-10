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
     */
    suspend fun request(url: String, method: String, body: String?): HttpResponse
}

/**
 * Produktiv-Implementierung über [HttpURLConnection] (keine neue Dependency).
 */
class UrlConnectionHttpClient : HttpClient {

    override suspend fun request(url: String, method: String, body: String?): HttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method.uppercase()
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
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

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 15_000
    }
}

/**
 * Executor für `http_request` — feuert einen HTTP-Aufruf ab.
 *
 * Param-Vertrag:
 * - `url`    : String, **erforderlich** — Ziel-URL.
 * - `method` : String, optional (default `"GET"`) — HTTP-Methode.
 * - `body`   : String, optional — Request-Body (z. B. JSON).
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

        return try {
            val response = withContext(ioDispatcher) { client.request(url, method, body) }
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

    companion object {
        private const val TAG = "HttpRequestAction"
    }
}
