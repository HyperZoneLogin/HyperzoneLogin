/*
 * This file is part of HyperZoneLogin, licensed under the GNU Affero General Public License v3.0 or later.
 *
 * Copyright (C) ksqeib (庆灵) <ksqeib@qq.com>
 * Copyright (C) contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.JavaExec
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    java
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

dependencies {
    compileOnly(libs.velocityApi)
    compileOnly(project(":api"))
    compileOnly(project(":velocity"))

    runtimeOnly(libs.velocityProxy)
    runtimeOnly(libs.velocityApi)
    runtimeOnly(project(":api"))
    runtimeOnly(project(":velocity"))
    runtimeOnly(project(":auth-floodgate"))
    runtimeOnly(project(":auth-offline"))
    runtimeOnly(project(":auth-yggd"))
    runtimeOnly(project(":data-merge"))
    runtimeOnly(project(":profile-skin"))
    runtimeOnly(project(":safe"))

    // Runtime libraries that are normally downloaded by the main plugin bootstrap.
    // In the dev environment we provide them directly on the classpath so no network
    // access is required and class loading works without the bootstrap download step.
    runtimeOnly(libs.configurateExtraKotlin)
    runtimeOnly(libs.configurateHocon)
    runtimeOnly(libs.configurateGson)
    runtimeOnly(libs.configurateYaml)
    runtimeOnly(libs.exposedCore)
    runtimeOnly(libs.exposedJdbc)
    runtimeOnly(libs.sqliteJdbc)
    runtimeOnly(libs.mysql)
    runtimeOnly(libs.mariadb)
    runtimeOnly(libs.hikari)
    runtimeOnly(libs.angusMail)
    runtimeOnly(libs.googleAuth)
    runtimeOnly(libs.h2)
    runtimeOnly(libs.asm)
    runtimeOnly(libs.asmCommons)
    runtimeOnly(libs.jarRelocator)
    runtimeOnly(libs.bstatsVelocity)
}

val runDir = layout.projectDirectory.dir("run")
val runPluginsDir = runDir.dir("plugins")
val vcRuntestPluginJar = tasks.named<Jar>("jar")

fun writeIfMissing(target: File, content: String) {
    if (target.exists()) return
    target.parentFile.mkdirs()
    target.writeText(content)
}

fun stageRunDirectoryConfigFiles(runDirFile: File) {
    writeIfMissing(
        runDirFile.resolve("velocity.toml"),
        """
        config-version = "2.8"
        bind = "127.0.0.1:25575"
        motd = "<green>HyperZoneLogin VC Runtest"
        show-max-players = 1
        online-mode = false
        force-key-authentication = false
        prevent-client-proxy-connections = false
        player-info-forwarding-mode = "modern"
        forwarding-secret-file = "forwarding.secret"
        announce-forge = false
        kick-existing-players = false
        ping-passthrough = "DISABLED"
        sample-players-in-ping = false
        enable-player-address-logging = false

        [packet-limiter]
        interval = 7
        packets-per-second = -1
        bytes-per-second = -1
        decompressed-bytes-per-second = 5242880

        [servers]
        play = "127.0.0.1:30067"
        outpre-auth = "127.0.0.1:30066"
        try = [
            "play"
        ]

        [forced-hosts]

        [advanced]
        compression-threshold = 256
        compression-level = -1
        login-ratelimit = 0
        connection-timeout = 5000
        read-timeout = 30000
        haproxy-protocol = false
        tcp-fast-open = false
        bungee-plugin-message-channel = true
        show-ping-requests = false
        failover-on-unexpected-server-disconnect = true
        announce-proxy-commands = true
        log-command-executions = false
        log-player-connections = true
        accepts-transfers = false
        enable-reuse-port = false
        command-rate-limit = 50
        forward-commands-if-rate-limited = true
        kick-after-rate-limited-commands = 0
        tab-complete-rate-limit = 10
        kick-after-rate-limited-tab-completes = 0

        [query]
        enabled = false
        port = 25565
        map = "Velocity"
        show-plugins = false
        """.trimIndent() + "\n"
    )

    writeIfMissing(
        runDirFile.resolve("forwarding.secret"),
        "xQHleQQvdFNe\n"
    )

    writeIfMissing(
        runDirFile.resolve("plugins/bStats/config.txt"),
        """
        enabled=false
        server-uuid=a8c7c030-3822-47d9-a9fc-124c59009ca8
        log-errors=false
        log-sent-data=false
        log-response-status-text=false
        """.trimIndent() + "\n"
    )

    writeIfMissing(
        runDirFile.resolve("plugins/hyperzonelogin/start.conf"),
        """
        language="zh_cn"
        format=hocon
        ready=true
        """.trimIndent() + "\n"
    )
}

tasks.named<Jar>("jar") {
    from(sourceSets.named("main").get().output)
    from(layout.projectDirectory.dir("src/main/resources")) {
        include("velocity-plugin.json")
    }
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
}

val installVcRuntestPlugin = tasks.register<Copy>("installVcRuntestPlugin") {
    group = "application"
    description = "Installs the stable vc-runtest bridge plugin jar into the local Velocity plugins directory."

    dependsOn(vcRuntestPluginJar)
    into(runPluginsDir)
    from(vcRuntestPluginJar.flatMap { it.archiveFile }) {
        rename { "vc-runtest.jar" }
    }

    doFirst {
        runPluginsDir.asFile.mkdirs()
        runPluginsDir.asFile.listFiles()
            ?.filter { file -> file.isFile && (file.name.startsWith("HyperZoneLogin-") || file.name == "vc-runtest.jar") }
            ?.forEach { file ->
                if (file.name.startsWith("HyperZoneLogin-")) {
                    file.delete()
                }
            }
    }
}

tasks.register<JavaExec>("runVelocity") {
    group = "application"
    description = "Runs a local Velocity proxy with a persistent vc-runtest bridge plugin and direct module classpath loading."

    dependsOn(installVcRuntestPlugin)

    doFirst {
        val runDirFile = runDir.asFile
        runDirFile.mkdirs()
        stageRunDirectoryConfigFiles(runDirFile)
    }

    workingDir = runDir.asFile
    standardInput = System.`in`
    classpath = sourceSets.named("main").get().runtimeClasspath
    mainClass.set("com.velocitypowered.proxy.Velocity")
    jvmArgs(
        "-Dfile.encoding=UTF-8",
        "-Dsun.stdout.encoding=UTF-8",
        "-Dsun.stderr.encoding=UTF-8",
        "--enable-native-access=ALL-UNNAMED",
    )
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    })
}
