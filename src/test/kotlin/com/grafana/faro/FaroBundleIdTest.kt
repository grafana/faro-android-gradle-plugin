package com.grafana.faro

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FaroBundleIdTest {

    @Test
    fun `format uses versionCode before versionName`() {
        assertEquals(
            "com.example.app@42@1.0.0",
            FaroBundleId.format("com.example.app", "42", "1.0.0"),
        )
    }

    @Test
    fun `validate accepts encoded triple`() {
        assertTrue(FaroBundleId.validate("com.example.app@42@1.0.0"))
    }

    @Test
    fun `validate rejects git-sha style ids`() {
        assertFalse(FaroBundleId.validate("abc123deadbeef"))
        assertFalse(FaroBundleId.validate("release-2024-06-04"))
    }

    @Test
    fun `validate rejects wrong segment count`() {
        assertFalse(FaroBundleId.validate("com.app@1"))
        assertFalse(FaroBundleId.validate("com.app@1@2@3"))
    }

    @Test
    fun `validate rejects non-numeric versionCode`() {
        assertFalse(FaroBundleId.validate("com.app@beta@1.0"))
    }
}
