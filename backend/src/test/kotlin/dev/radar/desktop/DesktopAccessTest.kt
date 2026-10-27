package dev.radar.desktop

import kotlin.test.*

class DesktopAccessTest {
    @Test fun `pending native handshakes are bounded`() {
        val access = DesktopAccess("process")
        val codes = List(9) { access.issueLaunch() }
        assertFalse(access.consumeLaunch(codes.first()))
        codes.drop(1).forEach { assertTrue(access.consumeLaunch(it)) }
    }

    @Test fun `concurrent native opens keep both pending launch handshakes valid`() {
        val access = DesktopAccess("process")
        val first = access.issueLaunch()
        val second = access.issueLaunch()
        assertTrue(access.consumeLaunch(first))
        assertTrue(access.consumeLaunch(second))
        assertFalse(access.consumeLaunch(first))
        assertFalse(access.consumeLaunch(second))
    }

    @Test fun `launch codes are single use and separate from process capability`() {
        val access = DesktopAccess("process")
        val launch = access.issueLaunch()
        assertNotEquals("process", launch)
        assertFalse(access.consumeLaunch("process"))
        assertTrue(access.consumeLaunch(launch))
        assertFalse(access.consumeLaunch(launch))
        val next = access.issueLaunch()
        assertNotEquals(launch, next)
        assertTrue(access.consumeLaunch(next))
    }
}
