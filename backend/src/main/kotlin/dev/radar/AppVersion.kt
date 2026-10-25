package dev.radar

import java.util.Properties

/** Generated from the root appVersion by Gradle for every runtime adapter. */
object AppVersion {
    val value: String = Properties().apply {
        AppVersion::class.java.getResourceAsStream("/hradar-version.properties").use {
            requireNotNull(it) { "Missing generated application version" }
            load(it)
        }
    }.getProperty("appVersion").also { require(!it.isNullOrBlank()) }
}
