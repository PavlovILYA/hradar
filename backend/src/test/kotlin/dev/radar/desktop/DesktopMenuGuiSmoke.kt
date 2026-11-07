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
        val opened = java.util.concurrent.LinkedBlockingQueue<DesktopDestination>()
        val quit = CountDownLatch(1)
        val menu = checkNotNull(DesktopMenu.install({ opened.add(it) }, { quit.countDown() }))
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
            val destinations = listOf(opened.poll(5, TimeUnit.SECONDS), opened.poll(5, TimeUnit.SECONDS)).toSet()
            check(destinations == DesktopDestination.entries.toSet())
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
