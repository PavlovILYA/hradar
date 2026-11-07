package dev.radar.desktop

import java.awt.EventQueue
import java.awt.SystemTray
import java.awt.event.ActionEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicit GUI smoke, separate from build/test so CI headless/unit tests stay GUI-free. */
object DesktopMenuGuiSmoke {
    @JvmStatic fun main(args: Array<String>) {
        val before = SystemTray.getSystemTray().trayIcons.size
        val opened = java.util.concurrent.LinkedBlockingQueue<String>()
        val access = DesktopAccess("synthetic-gui-process")
        val quit = CountDownLatch(1)
        val menu = checkNotNull(DesktopMenu.install({ DesktopRuntime.openInterface(access, it) { url -> opened.add(url) } }, { quit.countDown() }))
        try {
            check(SystemTray.getSystemTray().trayIcons.size == before + 1)
            EventQueue.invokeAndWait {
                check(menu.popup.getItem(1).label == "Запускается")
                check(!menu.popup.getItem(2).isEnabled && menu.popup.getItem(5).isEnabled)
            }
            menu.ready()
            EventQueue.invokeAndWait {
                check(menu.popup.getItem(1).label == "Сервер запущен")
                for (index in listOf(2, 3)) {
                    val item = menu.popup.getItem(index)
                    check(item.isEnabled)
                    item.actionListeners.forEach { it.actionPerformed(ActionEvent(item, ActionEvent.ACTION_PERFORMED, item.label)) }
                }
            }
            val uris = List(2) { java.net.URI(checkNotNull(opened.poll(5, TimeUnit.SECONDS))) }
            check(uris.map { it.query }.toSet() == setOf(null, "screen=application"))
            check(uris.all { it.host == "hradar.localhost" && it.port == 1709 })
            check(uris.map { it.fragment }.toSet().size == 2)
            for (uri in uris) {
                val code = uri.fragment.removePrefix("launch=")
                check(code != access.capability)
                check(access.consumeLaunch(code) && !access.consumeLaunch(code))
            }
            EventQueue.invokeAndWait {
                val item = menu.popup.getItem(5)
                repeat(2) { item.actionListeners.forEach { it.actionPerformed(ActionEvent(item, ActionEvent.ACTION_PERFORMED, item.label)) } }
            }
            check(quit.await(5, TimeUnit.SECONDS))
            EventQueue.invokeAndWait {
                check(menu.popup.getItem(1).label == "Завершается")
                check(!menu.popup.getItem(2).isEnabled && !menu.popup.getItem(5).isEnabled)
            }
        } finally { menu.close() }
        check(SystemTray.getSystemTray().trayIcons.size == before)
        println("Real macOS SystemTray installed, actions dispatched, states checked, icon removed")
        DesktopShutdownSmoke.verify()
        // No System.exit: the command must finish without a retained AWT thread.
    }
}
