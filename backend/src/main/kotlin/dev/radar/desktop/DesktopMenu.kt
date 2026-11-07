package dev.radar.desktop

import java.awt.*
import java.awt.image.BaseMultiResolutionImage
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicReference

internal enum class DesktopDestination { HOME, SETTINGS }
internal enum class DesktopMenuState(val label: String) {
    STARTING("Запускается"), RUNNING("Сервер запущен"), STOPPING("Завершается")
}

/** Same actions for the native menu and account-free lifecycle tests. */
internal class DesktopMenuActions(
    private val browse: (DesktopDestination) -> Unit,
    private val shutdown: () -> Unit,
) {
    private val current = AtomicReference(DesktopMenuState.STARTING)
    val state get() = current.get()
    var changed: () -> Unit = {}
    fun ready() { if (current.compareAndSet(DesktopMenuState.STARTING, DesktopMenuState.RUNNING)) changed() }
    fun stopping() { if (current.getAndSet(DesktopMenuState.STOPPING) != DesktopMenuState.STOPPING) changed() }
    fun open(destination: DesktopDestination) { if (state == DesktopMenuState.RUNNING) browse(destination) }
    fun quit() {
        if (current.getAndSet(DesktopMenuState.STOPPING) == DesktopMenuState.STOPPING) return
        changed()
        shutdown()
    }
}

/** No addresses, capabilities or process ownership in the AWT adapter. */
internal class DesktopMenu private constructor(private val actions: DesktopMenuActions) : AutoCloseable {
    internal val popup = PopupMenu()
    private val status = MenuItem()
    private val home = MenuItem("Открыть hradar")
    private val settings = MenuItem("Настройки")
    private val quit = MenuItem("Завершить hradar")
    private val tray = SystemTray.getSystemTray()
    private val icon = TrayIcon(image(), "hradar", popup)
    private var closed = false

    init {
        popup.add(MenuItem("hradar").apply { isEnabled = false })
        popup.add(status.apply { isEnabled = false })
        popup.add(home.apply { addActionListener { browse(DesktopDestination.HOME) } })
        popup.add(settings.apply { addActionListener { browse(DesktopDestination.SETTINGS) } })
        popup.addSeparator()
        popup.add(quit.apply { addActionListener { actions.quit() } })
        actions.changed = { EventQueue.invokeLater { if (!closed) render() } }
        render()
        try { tray.add(icon) } catch (failure: Exception) {
            tray.remove(icon); popup.removeNotify(); throw failure
        }
    }
    private fun render() {
        status.label = actions.state.label
        home.isEnabled = actions.state == DesktopMenuState.RUNNING
        settings.isEnabled = home.isEnabled
        quit.isEnabled = actions.state != DesktopMenuState.STOPPING
    }
    private fun browse(destination: DesktopDestination) {
        // LaunchServices may block; keep the native menu responsive.
        Thread({
            try { actions.open(destination) } catch (_: Exception) {
                EventQueue.invokeLater {
                    if (!closed) javax.swing.JOptionPane.showMessageDialog(null,
                        "Не удалось открыть браузер. Повторите открытие через меню hradar.", "hradar", javax.swing.JOptionPane.WARNING_MESSAGE)
                }
            }
        }, "hradar-browser").start()
    }
    fun ready() = actions.ready()
    fun stopping() = actions.stopping()
    override fun close() = onEdt {
        if (!closed) {
            closed = true
            actions.stopping()
            actions.changed = {}
            tray.remove(icon)
            popup.removeNotify()
        }
    }

    companion object {
        internal fun shouldInstall(desktop: Boolean, os: String, headless: Boolean): Boolean {
            if (!desktop || !os.startsWith("Mac")) return false
            check(!headless) { "Menu Bar требует графический сеанс macOS" }
            return true
        }
        fun install(browse: (DesktopDestination) -> Unit, shutdown: () -> Unit): DesktopMenu? {
            // Do not even initialize the AWT toolkit in ordinary backend/dev mode.
            if (!DesktopRuntime.enabled) return null
            if (!shouldInstall(true, System.getProperty("os.name"), GraphicsEnvironment.isHeadless())) return null
            check(SystemTray.isSupported()) { "macOS Menu Bar недоступен" }
            var menu: DesktopMenu? = null
            onEdt { menu = DesktopMenu(DesktopMenuActions(browse, shutdown)) }
            return menu
        }
        private fun onEdt(action: () -> Unit) {
            if (EventQueue.isDispatchThread()) action() else EventQueue.invokeAndWait(action)
        }
        internal fun image(): BaseMultiResolutionImage {
            val rows = checkNotNull(DesktopMenu::class.java.getResourceAsStream("/desktop/menu-h.txt"))
                .bufferedReader().use { it.readLines() }
            check(rows.size == 18 && rows.all { it.length == 18 })
            return BaseMultiResolutionImage(*intArrayOf(1, 2).map { scale ->
                BufferedImage(18 * scale, 18 * scale, BufferedImage.TYPE_INT_ARGB).apply {
                    for ((y, row) in rows.withIndex()) for ((x, pixel) in row.withIndex()) {
                        if (pixel == '#') for (dy in 0 until scale) for (dx in 0 until scale)
                            setRGB(x * scale + dx, y * scale + dy, 0xff000000.toInt())
                    }
                }
            }.toTypedArray())
        }
    }
}
