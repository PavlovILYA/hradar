package dev.radar.desktop

import kotlin.test.*

class DesktopMenuTest {
    @Test fun `actions follow readiness and quit only once`() {
        val opened = mutableListOf<DesktopDestination>()
        var quits = 0
        val actions = DesktopMenuActions(opened::add) { quits++ }
        assertEquals(DesktopMenuState.STARTING, actions.state)
        actions.open(DesktopDestination.HOME)
        assertTrue(opened.isEmpty())
        actions.ready()
        actions.open(DesktopDestination.HOME)
        actions.open(DesktopDestination.SETTINGS)
        assertEquals(listOf(DesktopDestination.HOME, DesktopDestination.SETTINGS), opened)
        actions.quit(); actions.quit(); actions.ready()
        actions.open(DesktopDestination.HOME)
        assertEquals(DesktopMenuState.STOPPING, actions.state)
        assertEquals(1, quits)
        assertEquals(2, opened.size)
    }

    @Test fun `quit during startup cannot become ready`() {
        val actions = DesktopMenuActions({ error("must not open") }, {})
        actions.quit(); actions.ready()
        assertEquals(DesktopMenuState.STOPPING, actions.state)
    }

    @Test fun `dev and headless do not construct AWT menu`() {
        assertNull(DesktopMenu.install({ error("dev browse") }, { error("dev quit") }))
        assertFalse(DesktopMenu.shouldInstall(false, "Mac OS X", false))
        assertFalse(DesktopMenu.shouldInstall(true, "Linux", false))
        assertTrue(DesktopMenu.shouldInstall(true, "Mac OS X", false))
        // A packaged headless launch must fail visibly/cleanly, not silently skip.
        assertFailsWith<IllegalStateException> { DesktopMenu.shouldInstall(true, "Mac OS X", true) }
    }

    @Test fun `browser destination retains launcher fragment and has fixed settings query`() {
        val home = DesktopRuntime.launchUri("synthetic-token", DesktopDestination.HOME)
        val settings = DesktopRuntime.launchUri("synthetic-token", DesktopDestination.SETTINGS)
        assertEquals(home.authority, settings.authority)
        assertEquals(home.path, settings.path)
        assertEquals("launch=synthetic-token", settings.fragment)
        assertEquals("screen=application", settings.query)
        assertNull(home.query)
    }

    @Test fun `template glyph has transparent margins and Retina variants`() {
        val image = DesktopMenu.image()
        assertEquals(listOf(18, 36), image.resolutionVariants.map { it.getWidth(null) })
        assertEquals(0, (image.resolutionVariants[0] as java.awt.image.BufferedImage).getRGB(0, 0))
    }
}
