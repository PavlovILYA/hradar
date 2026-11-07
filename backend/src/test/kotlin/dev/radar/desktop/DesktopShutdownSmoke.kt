package dev.radar.desktop

import dev.radar.runApplication
import java.awt.EventQueue
import java.awt.SystemTray
import java.awt.event.ActionEvent
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Child JVMs prove Quit cleans real services even with a stalled startup adapter. */
object DesktopShutdownSmoke {
    @JvmStatic fun main(args: Array<String>) {
        val stalled = CountDownLatch(1)
        val never = CountDownLatch(1)
        Thread({
            check(stalled.await(10, TimeUnit.SECONDS))
            EventQueue.invokeAndWait {
                val popup = SystemTray.getSystemTray().trayIcons.single().popupMenu
                val quit = popup.getItem(5)
                repeat(2) { quit.actionListeners.forEach { it.actionPerformed(ActionEvent(quit, ActionEvent.ACTION_PERFORMED, quit.label)) } }
            }
        }, "smoke-quit").apply { isDaemon = true; start() }
        runApplication(
            openBrowser = { stalled.countDown(); never.await() },
            probeReady = { if (args.single() == "health") { stalled.countDown(); never.await() }; true },
        )
    }

    fun verify() {
        for (scenario in listOf("health", "browser")) {
            val home = Files.createTempDirectory("hradar-shutdown-smoke-")
            val port = ServerSocket(0).use { it.localPort }
            val log = home.resolve("process.log")
            var completed = false
            try {
                val command = listOf(
                    Path.of(System.getProperty("java.home"), "bin/java").toString(),
                    "-Dhradar.desktop=true", "-Dhradar.smoke=true", "-Dhradar.smokePort=$port",
                    "-Duser.home=$home", "-Dapple.awt.enableTemplateImages=true",
                    "-cp", System.getProperty("java.class.path"), DesktopShutdownSmoke::class.java.name, scenario,
                )
                val builder = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile())
                builder.environment().keys.removeIf { it.startsWith("TG_") }
                val process = builder.start()
                try {
                    check(process.waitFor(20, TimeUnit.SECONDS)) { "$scenario shutdown hung (isolated log: $log)" }
                    check(process.exitValue() == 0) { "$scenario failed (isolated log: $log)" }
                    check(!Files.exists(home.resolve(".hradar/desktop-instance.json")))
                    ServerSocket(port).close()
                    checkNotNull(SessionLease.acquire(home.resolve(".hradar/tdlib"))).close()
                    completed = true
                    println("Menu quit while $scenario stalled: JVM exited, port/lease released, marker removed")
                } finally { if (process.isAlive) { process.destroyForcibly(); process.waitFor() } }
            } finally { if (completed) home.toFile().deleteRecursively() }
        }
    }
}
