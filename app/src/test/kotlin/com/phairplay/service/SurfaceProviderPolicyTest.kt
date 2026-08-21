package com.phairplay.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceProviderPolicyTest {

    @Test
    fun `clear allowed only for registered owner`() {
        val ownerA = Any()
        val ownerB = Any()
        assertTrue(SurfaceProviderPolicy.shouldClear(ownerA, ownerA))
        assertFalse(SurfaceProviderPolicy.shouldClear(ownerA, ownerB))
        assertFalse(SurfaceProviderPolicy.shouldClear(null, ownerA))
    }
}
