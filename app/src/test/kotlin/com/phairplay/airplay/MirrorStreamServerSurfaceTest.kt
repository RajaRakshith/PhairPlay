package com.phairplay.airplay

import com.phairplay.airplay.handshake.shouldRebuildForSurface
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorStreamServerSurfaceTest {

    @Test
    fun `rebuild when surface identity changes`() {
        val old = Any()
        val new = Any()
        assertTrue(shouldRebuildForSurface(new, old))
    }

    @Test
    fun `no rebuild when both null`() {
        assertFalse(shouldRebuildForSurface(null, null))
    }

    @Test
    fun `rebuild when returning from background null to new surface`() {
        assertTrue(shouldRebuildForSurface(Any(), null))
    }
}
