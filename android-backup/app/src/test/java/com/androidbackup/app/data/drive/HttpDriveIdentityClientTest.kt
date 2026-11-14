package com.androidbackup.app.data.drive

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class HttpDriveIdentityClientTest {

    private class FakeHttpConnectionHandle(
        private val statusCode: Int,
        private val body: String = "",
        private val errorBody: String? = null,
        private val exceptionOnResponseCode: IOException? = null
    ) : HttpConnectionHandle {
        var requestedMethod: String? = null
        var connectTimeoutMs: Int = -1
        var readTimeoutMs: Int = -1
        var useCachesFlag: Boolean? = null
        val requestHeaders = mutableMapOf<String, String>()
        var disconnected: Boolean = false

        override fun setRequestMethod(method: String) {
            requestedMethod = method
        }

        override fun setConnectTimeout(timeoutMs: Int) {
            connectTimeoutMs = timeoutMs
        }

        override fun setReadTimeout(timeoutMs: Int) {
            readTimeoutMs = timeoutMs
        }

        override fun setUseCaches(useCaches: Boolean) {
            useCachesFlag = useCaches
        }

        override fun setRequestProperty(key: String, value: String) {
            requestHeaders[key] = value
        }

        override fun getResponseCode(): Int {
            exceptionOnResponseCode?.let { throw it }
            return statusCode
        }

        override fun getInputStream(): InputStream =
            ByteArrayInputStream(body.toByteArray(StandardCharsets.UTF_8))

        override fun getErrorStream(): InputStream? =
            errorBody?.let { ByteArrayInputStream(it.toByteArray(StandardCharsets.UTF_8)) }

        override fun disconnect() {
            disconnected = true
        }
    }

    @Test
    fun fetchUserIdentity_200WithFullFields_parsesIdentityAndSetsBearerHeader() = runTest {
        val secretToken = "ya29.secret-test-token-123"
        val handle = FakeHttpConnectionHandle(
            statusCode = 200,
            body = """
                {
                  "user": {
                    "displayName": "Mario Rossi",
                    "emailAddress": "mario.rossi@example.com",
                    "permissionId": "12345678901234567890"
                  }
                }
            """.trimIndent()
        )
        var capturedUrl: String? = null
        val client = HttpDriveIdentityClient(
            connectionFactory = { url ->
                capturedUrl = url
                handle
            },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        val identity = client.fetchUserIdentity(secretToken)

        assertEquals(HttpDriveIdentityClient.DRIVE_ABOUT_USER_URL, capturedUrl)
        assertEquals("GET", handle.requestedMethod)
        assertEquals(false, handle.useCachesFlag)
        assertEquals("Bearer $secretToken", handle.requestHeaders["Authorization"])
        assertEquals("application/json", handle.requestHeaders["Accept"])
        assertTrue(handle.disconnected)
        assertEquals("12345678901234567890", identity.permissionId)
        assertEquals("mario.rossi@example.com", identity.emailAddress)
        assertEquals("Mario Rossi", identity.displayName)
    }

    @Test
    fun fetchUserIdentity_200WithNullAndMissingOptionalFields_returnsKotlinNullNeverLiteralNull() = runTest {
        val handleWithJsonNulls = FakeHttpConnectionHandle(
            statusCode = 200,
            body = """
                {
                  "user": {
                    "displayName": null,
                    "emailAddress": null,
                    "permissionId": "perm-no-email"
                  }
                }
            """.trimIndent()
        )
        val client1 = HttpDriveIdentityClient(
            connectionFactory = { handleWithJsonNulls },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        val identity1 = client1.fetchUserIdentity("valid-token")
        assertEquals("perm-no-email", identity1.permissionId)
        assertNull(identity1.emailAddress)
        assertNull(identity1.displayName)

        val handleWithOmittedFields = FakeHttpConnectionHandle(
            statusCode = 200,
            body = """{"user": {"permissionId": "perm-only", "emailAddress": "  "}}"""
        )
        val client2 = HttpDriveIdentityClient(
            connectionFactory = { handleWithOmittedFields },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        val identity2 = client2.fetchUserIdentity("valid-token")
        assertEquals("perm-only", identity2.permissionId)
        assertNull(identity2.emailAddress)
        assertNull(identity2.displayName)
    }

    @Test
    fun fetchUserIdentity_missingPermissionId_throwsMissingPermissionId() = runTest {
        val handle = FakeHttpConnectionHandle(
            statusCode = 200,
            body = """{"user": {"displayName": "No Id", "emailAddress": "noid@example.com"}}"""
        )
        val client = HttpDriveIdentityClient(
            connectionFactory = { handle },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        try {
            client.fetchUserIdentity("valid-token")
            fail("Avrebbe dovuto lanciare MissingPermissionId")
        } catch (_: DriveIdentityException.MissingPermissionId) {
            assertTrue(handle.disconnected)
        }
    }

    @Test
    fun fetchUserIdentity_malformedJson_throwsMalformedResponse() = runTest {
        val handle = FakeHttpConnectionHandle(statusCode = 200, body = "{not-json")
        val client = HttpDriveIdentityClient(
            connectionFactory = { handle },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        try {
            client.fetchUserIdentity("valid-token")
            fail("Avrebbe dovuto lanciare MalformedResponse")
        } catch (_: DriveIdentityException.MalformedResponse) {
            assertTrue(handle.disconnected)
        }
    }

    @Test
    fun fetchUserIdentity_401And403And500_throwTypedExceptionsWithoutLeakingToken() = runTest {
        val secretToken = "ya29.super-secret-do-not-leak"

        val client401 = HttpDriveIdentityClient(
            connectionFactory = {
                FakeHttpConnectionHandle(401, errorBody = """{"error":{"message":"Invalid Credentials"}}""")
            },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        try {
            client401.fetchUserIdentity(secretToken)
            fail("Atteso Unauthorized")
        } catch (e: DriveIdentityException.Unauthorized) {
            assertFalse(e.message.orEmpty().contains(secretToken))
        }

        val client403 = HttpDriveIdentityClient(
            connectionFactory = {
                FakeHttpConnectionHandle(403, errorBody = """{"error":{"message":"Insufficient Permission"}}""")
            },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        try {
            client403.fetchUserIdentity(secretToken)
            fail("Atteso Forbidden")
        } catch (e: DriveIdentityException.Forbidden) {
            assertTrue(e.message.orEmpty().contains("Insufficient Permission"))
            assertFalse(e.message.orEmpty().contains(secretToken))
        }

        val client500 = HttpDriveIdentityClient(
            connectionFactory = {
                FakeHttpConnectionHandle(500, errorBody = """{"error":{"message":"Backend down"}}""")
            },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        try {
            client500.fetchUserIdentity(secretToken)
            fail("Atteso HttpError")
        } catch (e: DriveIdentityException.HttpError) {
            assertEquals(500, e.statusCode)
            assertFalse(e.message.orEmpty().contains(secretToken))
        }
    }

    @Test
    fun fetchUserIdentity_timeoutAndIoError_throwTypedNetworkExceptions() = runTest {
        val timeoutClient = HttpDriveIdentityClient(
            connectionFactory = {
                FakeHttpConnectionHandle(0, exceptionOnResponseCode = SocketTimeoutException("timed out"))
            },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        try {
            timeoutClient.fetchUserIdentity("token")
            fail("Atteso Timeout")
        } catch (_: DriveIdentityException.Timeout) {
            // ok
        }

        val ioClient = HttpDriveIdentityClient(
            connectionFactory = {
                FakeHttpConnectionHandle(0, exceptionOnResponseCode = IOException("network unreachable"))
            },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        try {
            ioClient.fetchUserIdentity("token")
            fail("Atteso NetworkError")
        } catch (_: DriveIdentityException.NetworkError) {
            // ok
        }
    }

    @Test
    fun fetchUserIdentity_blankToken_throwsUnauthorizedWithoutOpeningConnection() = runTest {
        var opened = false
        val client = HttpDriveIdentityClient(
            connectionFactory = {
                opened = true
                FakeHttpConnectionHandle(200, "{}")
            },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        try {
            client.fetchUserIdentity("   ")
            fail("Atteso Unauthorized")
        } catch (_: DriveIdentityException.Unauthorized) {
            assertFalse(opened)
        }
    }
}
