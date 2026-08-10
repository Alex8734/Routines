package at.resch.routines.core.action

import at.resch.routines.domain.model.Action
import at.resch.routines.domain.model.ActionResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Unit tests for [HttpRequestActionExecutor].
 *
 * [HttpClient] is mocked so no real network calls are made. [UnconfinedTestDispatcher]
 * is injected as ioDispatcher so `withContext` dispatches eagerly inside runTest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HttpRequestActionExecutorTest {

    private lateinit var client: HttpClient
    private lateinit var executor: HttpRequestActionExecutor

    @Before
    fun setup() {
        client = mockk()
        // UnconfinedTestDispatcher processes coroutines eagerly — no advanceUntilIdle needed.
        executor = HttpRequestActionExecutor(
            client = client,
            ioDispatcher = UnconfinedTestDispatcher()
        )
    }

    // -----------------------------------------------------------------------
    // type discriminator
    // -----------------------------------------------------------------------

    @Test
    fun `type is http_request`() {
        assertEquals("http_request", executor.type)
    }

    // -----------------------------------------------------------------------
    // Missing / blank url → Failure (no client call)
    // -----------------------------------------------------------------------

    @Test
    fun `missing url param returns Failure without calling client`() = runTest {
        val action = Action(type = "http_request", params = emptyMap())

        val result = executor.execute(action)

        assertTrue(result is ActionResult.Failure)
        coVerify(exactly = 0) { client.request(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `blank url param returns Failure without calling client`() = runTest {
        val action = Action(type = "http_request", params = mapOf("url" to "   "))

        val result = executor.execute(action)

        assertTrue(result is ActionResult.Failure)
        coVerify(exactly = 0) { client.request(any(), any(), any(), any(), any()) }
    }

    // -----------------------------------------------------------------------
    // 2xx → Success carrying response body
    // -----------------------------------------------------------------------

    @Test
    fun `200 response returns Success with response body`() = runTest {
        coEvery { client.request(any(), any(), any(), any(), any()) } returns HttpResponse(200, "ok body")

        val action = Action(
            type = "http_request",
            params = mapOf("url" to "https://example.com")
        )

        val result = executor.execute(action)

        assertTrue(result is ActionResult.Success)
        assertEquals("ok body", (result as ActionResult.Success).output)
    }

    @Test
    fun `201 response returns Success`() = runTest {
        coEvery { client.request(any(), any(), any(), any(), any()) } returns HttpResponse(201, "created")

        val action = Action(
            type = "http_request",
            params = mapOf("url" to "https://api.example.com/items")
        )

        val result = executor.execute(action)

        assertTrue(result is ActionResult.Success)
        assertEquals("created", (result as ActionResult.Success).output)
    }

    @Test
    fun `299 boundary returns Success`() = runTest {
        coEvery { client.request(any(), any(), any(), any(), any()) } returns HttpResponse(299, "edge-2xx")

        val result = executor.execute(
            Action("http_request", mapOf("url" to "https://example.com"))
        )

        assertTrue(result is ActionResult.Success)
    }

    // -----------------------------------------------------------------------
    // Non-2xx → Failure
    // -----------------------------------------------------------------------

    @Test
    fun `400 response returns Failure`() = runTest {
        coEvery { client.request(any(), any(), any(), any(), any()) } returns HttpResponse(400, "bad request")

        val result = executor.execute(
            Action("http_request", mapOf("url" to "https://example.com"))
        )

        assertTrue(result is ActionResult.Failure)
    }

    @Test
    fun `500 response returns Failure`() = runTest {
        coEvery { client.request(any(), any(), any(), any(), any()) } returns HttpResponse(500, "server error")

        val result = executor.execute(
            Action("http_request", mapOf("url" to "https://example.com"))
        )

        assertTrue(result is ActionResult.Failure)
    }

    @Test
    fun `300 redirect returns Failure`() = runTest {
        coEvery { client.request(any(), any(), any(), any(), any()) } returns HttpResponse(301, "moved")

        val result = executor.execute(
            Action("http_request", mapOf("url" to "https://example.com"))
        )

        assertTrue(result is ActionResult.Failure)
    }

    @Test
    fun `non-2xx Failure reason contains status code`() = runTest {
        coEvery { client.request(any(), any(), any(), any(), any()) } returns HttpResponse(404, "not found")

        val result = executor.execute(
            Action("http_request", mapOf("url" to "https://example.com/missing"))
        ) as ActionResult.Failure

        assertTrue(
            "Failure reason should contain status code, was: ${result.reason}",
            result.reason.contains("404")
        )
    }

    // -----------------------------------------------------------------------
    // Client throws → Failure (no crash)
    // -----------------------------------------------------------------------

    @Test
    fun `client throws IOException — returns Failure without re-throwing`() = runTest {
        coEvery { client.request(any(), any(), any(), any(), any()) } throws IOException("Network unreachable")

        val result = executor.execute(
            Action("http_request", mapOf("url" to "https://example.com"))
        )

        assertTrue(result is ActionResult.Failure)
    }

    @Test
    fun `client throws — Failure carries the cause`() = runTest {
        val cause = IOException("timeout")
        coEvery { client.request(any(), any(), any(), any(), any()) } throws cause

        val result = executor.execute(
            Action("http_request", mapOf("url" to "https://example.com"))
        ) as ActionResult.Failure

        assertEquals(cause, result.cause)
    }

    // -----------------------------------------------------------------------
    // Default method "GET" when absent or blank
    // -----------------------------------------------------------------------

    @Test
    fun `missing method param defaults to GET`() = runTest {
        val methodSlot = slot<String>()
        coEvery { client.request(any(), capture(methodSlot), any(), any(), any()) } returns HttpResponse(200, "")

        val action = Action(
            type = "http_request",
            params = mapOf("url" to "https://example.com")
        )

        executor.execute(action)

        assertEquals("GET", methodSlot.captured)
    }

    @Test
    fun `blank method param defaults to GET`() = runTest {
        val methodSlot = slot<String>()
        coEvery { client.request(any(), capture(methodSlot), any(), any(), any()) } returns HttpResponse(200, "")

        val action = Action(
            type = "http_request",
            params = mapOf("url" to "https://example.com", "method" to "  ")
        )

        executor.execute(action)

        assertEquals("GET", methodSlot.captured)
    }

    // -----------------------------------------------------------------------
    // method and body forwarded to client
    // -----------------------------------------------------------------------

    @Test
    fun `explicit method POST is forwarded to client`() = runTest {
        val methodSlot = slot<String>()
        coEvery { client.request(any(), capture(methodSlot), any(), any(), any()) } returns HttpResponse(200, "")

        val action = Action(
            type = "http_request",
            params = mapOf("url" to "https://api.example.com", "method" to "POST")
        )

        executor.execute(action)

        assertEquals("POST", methodSlot.captured)
    }

    @Test
    fun `body param is forwarded to client`() = runTest {
        val bodySlot = slot<String?>()
        coEvery { client.request(any(), any(), captureNullable(bodySlot), any(), any()) } returns HttpResponse(200, "")

        val action = Action(
            type = "http_request",
            params = mapOf(
                "url" to "https://api.example.com",
                "method" to "POST",
                "body" to "{\"key\":\"value\"}"
            )
        )

        executor.execute(action)

        assertEquals("{\"key\":\"value\"}", bodySlot.captured)
    }

    @Test
    fun `missing body param forwarded as null`() = runTest {
        val bodySlot = slot<String?>()
        coEvery { client.request(any(), any(), captureNullable(bodySlot), any(), any()) } returns HttpResponse(200, "")

        val action = Action(
            type = "http_request",
            params = mapOf("url" to "https://example.com")
        )

        executor.execute(action)

        assertEquals(null, bodySlot.captured)
    }

    @Test
    fun `url is forwarded to client unchanged`() = runTest {
        val urlSlot = slot<String>()
        coEvery { client.request(capture(urlSlot), any(), any(), any(), any()) } returns HttpResponse(200, "")

        val url = "https://webhook.site/abc-123"
        val action = Action("http_request", mapOf("url" to url))

        executor.execute(action)

        assertEquals(url, urlSlot.captured)
    }

    // -----------------------------------------------------------------------
    // headers param parsing
    // -----------------------------------------------------------------------

    @Test
    fun `no headers param forwards an empty map`() = runTest {
        val headersSlot = slot<Map<String, String>>()
        coEvery {
            client.request(any(), any(), any(), capture(headersSlot), any())
        } returns HttpResponse(200, "")

        executor.execute(Action("http_request", mapOf("url" to "https://example.com")))

        assertTrue("Expected empty headers map, was: ${headersSlot.captured}", headersSlot.captured.isEmpty())
    }

    @Test
    fun `multiple header lines are parsed and forwarded correctly`() = runTest {
        val headersSlot = slot<Map<String, String>>()
        coEvery {
            client.request(any(), any(), any(), capture(headersSlot), any())
        } returns HttpResponse(200, "")

        val action = Action(
            "http_request",
            mapOf(
                "url" to "https://example.com",
                "headers" to "Content-Type: application/json\nAuthorization: Bearer abc"
            )
        )

        executor.execute(action)

        assertEquals(
            mapOf("Content-Type" to "application/json", "Authorization" to "Bearer abc"),
            headersSlot.captured
        )
    }

    @Test
    fun `header value containing a colon is split only on the first colon`() = runTest {
        val headersSlot = slot<Map<String, String>>()
        coEvery {
            client.request(any(), any(), any(), capture(headersSlot), any())
        } returns HttpResponse(200, "")

        val action = Action(
            "http_request",
            mapOf(
                "url" to "https://example.com",
                "headers" to "Location: https://example.com/redirect"
            )
        )

        executor.execute(action)

        assertEquals(
            mapOf("Location" to "https://example.com/redirect"),
            headersSlot.captured
        )
    }

    @Test
    fun `blank lines and lines without a colon are discarded from headers`() = runTest {
        val headersSlot = slot<Map<String, String>>()
        coEvery {
            client.request(any(), any(), any(), capture(headersSlot), any())
        } returns HttpResponse(200, "")

        val action = Action(
            "http_request",
            mapOf(
                "url" to "https://example.com",
                "headers" to "Content-Type: application/json\n\nNoColonHere\nAuthorization: Bearer abc"
            )
        )

        executor.execute(action)

        assertEquals(
            mapOf("Content-Type" to "application/json", "Authorization" to "Bearer abc"),
            headersSlot.captured
        )
    }

    @Test
    fun `header line with empty name is discarded`() = runTest {
        val headersSlot = slot<Map<String, String>>()
        coEvery {
            client.request(any(), any(), any(), capture(headersSlot), any())
        } returns HttpResponse(200, "")

        val action = Action(
            "http_request",
            mapOf(
                "url" to "https://example.com",
                "headers" to ": no-name-value\nAuthorization: Bearer abc"
            )
        )

        executor.execute(action)

        assertEquals(mapOf("Authorization" to "Bearer abc"), headersSlot.captured)
    }

    // -----------------------------------------------------------------------
    // timeoutSeconds param parsing
    // -----------------------------------------------------------------------

    @Test
    fun `valid timeoutSeconds is converted to milliseconds`() = runTest {
        val timeoutSlot = slot<Int>()
        coEvery {
            client.request(any(), any(), any(), any(), capture(timeoutSlot))
        } returns HttpResponse(200, "")

        val action = Action(
            "http_request",
            mapOf("url" to "https://example.com", "timeoutSeconds" to "10")
        )

        executor.execute(action)

        assertEquals(10_000, timeoutSlot.captured)
    }

    @Test
    fun `missing timeoutSeconds defaults to 15000ms`() = runTest {
        val timeoutSlot = slot<Int>()
        coEvery {
            client.request(any(), any(), any(), any(), capture(timeoutSlot))
        } returns HttpResponse(200, "")

        executor.execute(Action("http_request", mapOf("url" to "https://example.com")))

        assertEquals(HttpClient.DEFAULT_TIMEOUT_MS, timeoutSlot.captured)
    }

    @Test
    fun `non-numeric timeoutSeconds defaults to 15000ms`() = runTest {
        val timeoutSlot = slot<Int>()
        coEvery {
            client.request(any(), any(), any(), any(), capture(timeoutSlot))
        } returns HttpResponse(200, "")

        val action = Action(
            "http_request",
            mapOf("url" to "https://example.com", "timeoutSeconds" to "not-a-number")
        )

        executor.execute(action)

        assertEquals(HttpClient.DEFAULT_TIMEOUT_MS, timeoutSlot.captured)
    }

    @Test
    fun `zero or negative timeoutSeconds defaults to 15000ms`() = runTest {
        val timeoutSlot = slot<Int>()
        coEvery {
            client.request(any(), any(), any(), any(), capture(timeoutSlot))
        } returns HttpResponse(200, "")

        executor.execute(
            Action("http_request", mapOf("url" to "https://example.com", "timeoutSeconds" to "0"))
        )
        assertEquals(HttpClient.DEFAULT_TIMEOUT_MS, timeoutSlot.captured)

        executor.execute(
            Action("http_request", mapOf("url" to "https://example.com", "timeoutSeconds" to "-5"))
        )
        assertEquals(HttpClient.DEFAULT_TIMEOUT_MS, timeoutSlot.captured)
    }

    @Test
    fun `very large timeoutSeconds is clamped to MAX_TIMEOUT_SECONDS`() = runTest {
        val timeoutSlot = slot<Int>()
        coEvery {
            client.request(any(), any(), any(), any(), capture(timeoutSlot))
        } returns HttpResponse(200, "")

        val action = Action(
            "http_request",
            mapOf("url" to "https://example.com", "timeoutSeconds" to "999999")
        )

        executor.execute(action)

        assertEquals(HttpRequestActionExecutor.MAX_TIMEOUT_SECONDS * 1000, timeoutSlot.captured)
    }
}
