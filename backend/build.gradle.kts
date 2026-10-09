plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    application
}

group = "dev.radar"
version = "0.1.0-SNAPSHOT"

repositories { mavenCentral() }

val ktorVersion = "3.6.0"

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktorVersion")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation(files("libs/tdlib-java.jar"))
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(21) }

application {
    mainClass.set("dev.radar.ApplicationKt")
    // TDLib Java bindings load libtdjni.dylib via System.loadLibrary("tdjni").
    applicationDefaultJvmArgs = listOf(
        "-Djava.library.path=${layout.projectDirectory.dir("native").asFile.absolutePath}"
    )
}

tasks.test { useJUnitPlatform() }

tasks.register("checkTdlib") {
    group = "verification"
    doLast {
        check(file("libs/tdlib-java.jar").exists()) {
            "Missing TDLib Java bindings. Run ./scripts/build-tdlib-macos.sh first."
        }
        check(file("native/libtdjni.dylib").exists()) {
            "Missing TDLib native library. Run ./scripts/build-tdlib-macos.sh first."
        }
    }
}

tasks.named<JavaExec>("run") {
    dependsOn("checkTdlib")
    standardInput = System.`in`
}

tasks.named("compileKotlin") { dependsOn("checkTdlib") }
