package com.nuvio.app.features.trakt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class TraktAuthRepositoryTest {

    @Test
    fun `device authorization omits absent client secret`() {
        val json = Json { encodeDefaults = true }
        val publicRequest = json.encodeToString(TraktDeviceTokenRequest(code = "device", clientId = "app"))
        val legacyRequest = json.encodeToString(TraktDeviceTokenRequest(code = "device", clientId = "app", clientSecret = "secret"))

        assertFalse("client_secret" in publicRequest)
        assertTrue("\"client_secret\":\"secret\"" in legacyRequest)
    }

    @Test
    fun `HTTP 400 permanently invalidates a rejected refresh token`() {
        assertEquals(
            TraktTokenRefreshResponseAction.INVALIDATE,
            traktTokenRefreshResponseAction(400),
        )
    }

    @Test
    fun `successful token responses are accepted`() {
        assertEquals(
            TraktTokenRefreshResponseAction.ACCEPT,
            traktTokenRefreshResponseAction(200),
        )
        assertEquals(
            TraktTokenRefreshResponseAction.ACCEPT,
            traktTokenRefreshResponseAction(201),
        )
    }

    @Test
    fun `non-400 failures preserve credentials for a later attempt`() {
        listOf(401, 429, 500, 503).forEach { status ->
            assertEquals(
                TraktTokenRefreshResponseAction.TRANSIENT_FAILURE,
                traktTokenRefreshResponseAction(status),
            )
        }
    }
}
