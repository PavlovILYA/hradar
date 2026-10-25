plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    application
}

group = "dev.radar"
version = providers.gradleProperty("appVersion").get()

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
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.14.0")
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
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

tasks.test {
    useJUnitPlatform()
    // Account-free tests must not inherit a developer's Telegram credentials.
    environment = environment.filterKeys { !it.startsWith("TG_") }
}

tasks.register("checkTdlibJava") {
    group = "verification"
    doLast {
        check(file("libs/tdlib-java.jar").exists()) {
            "Missing TDLib Java bindings. Run ./scripts/build-tdlib-macos.sh first."
        }
    }
}

tasks.register("checkTdlib") {
    group = "verification"
    dependsOn("checkTdlibJava")
    doLast {
        check(file("native/libtdjni.dylib").exists()) {
            "Missing TDLib native library. Run ./scripts/build-tdlib-macos.sh first."
        }
    }
}

tasks.named<JavaExec>("run") {
    dependsOn("checkTdlib")
    standardInput = System.`in`
}

tasks.named("compileKotlin") { dependsOn("checkTdlibJava") }

// Production UI becomes a classpath resource; development doesn't need a frontend build.
val bundledWeb = providers.gradleProperty("bundledWeb").map { it.toBoolean() }.orElse(false)
val versionResource = tasks.register("generateVersionResource") {
    val output = layout.buildDirectory.file("generated/version/hradar-version.properties")
    inputs.property("appVersion", project.version.toString())
    outputs.file(output)
    doLast {
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText("appVersion=${project.version}\n")
        }
    }
}
val checkAppVersion = tasks.register<Exec>("checkAppVersion") {
    group = "verification"
    workingDir(rootProject.projectDir)
    commandLine("python3", "-B", "scripts/version.py", "check", "--expected-version", project.version.toString())
}
tasks.named("check") { dependsOn(checkAppVersion) }
tasks.processResources {
    dependsOn(checkAppVersion, versionResource)
    from(versionResource)

    if (bundledWeb.get()) {
        from(rootProject.layout.projectDirectory.dir("frontend/dist")) { into("web") }
        doFirst { check(rootProject.file("frontend/dist/index.html").isFile) { "Build frontend production assets first" } }
    }
}
tasks.register<Sync>("stageDesktop") {
    dependsOn("jar")
    into(layout.buildDirectory.dir("desktop-input"))
    from(tasks.jar)
    from(configurations.runtimeClasspath)
}
