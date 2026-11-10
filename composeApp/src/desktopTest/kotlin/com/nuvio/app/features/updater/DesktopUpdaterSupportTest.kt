package com.nuvio.app.features.updater

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopUpdaterSupportTest {
    @Test
    fun onlyAppleSiliconMacsMatchTheForkDmg() {
        assertTrue(isArm64DesktopArchitecture("aarch64"))
        assertTrue(isArm64DesktopArchitecture("arm64"))
        assertFalse(isArm64DesktopArchitecture("x86_64"))
        assertFalse(isArm64DesktopArchitecture("amd64"))
    }
}
