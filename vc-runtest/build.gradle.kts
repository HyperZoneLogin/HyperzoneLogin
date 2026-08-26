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

import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.JavaExec
import org.gradle.jvm.toolchain.JavaLanguageVersion
import java.util.Locale

plugins {
    java
}

dependencies {
    runtimeOnly(libs.velocityProxy)
    runtimeOnly(libs.velocityApi)
}

val runDir = layout.projectDirectory.dir("run")
val velocityToml = runDir.file("velocity.toml")
val runtimeDependencyProjects = listOf(
    project(":velocity"),
    project(":auth-offline"),
    project(":data-merge"),
)

fun cacheFileName(groupId: String, artifactId: String, version: String): String =
    (groupId + "-" + artifactId + "-" + version + ".jar")
        .lowercase(Locale.ROOT)
        .replace(':', '-')
        .replace('.', '-')

fun velocityTomlContent(): String =
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

fun bStatsConfigContent(): String =
    """
        enabled=false
        server-uuid=a8c7c030-3822-47d9-a9fc-124c59009ca8
        log-errors=false
        log-sent-data=false
        log-response-status-text=false
    """.trimIndent() + "\n"

fun startConfContent(): String =
    """
    language="zh_cn"
    format=hocon
    ready=true
    """.trimIndent() + "\n"

fun stageVelocityRunDirectory(runDirFile: File, overwriteConfigFiles: Boolean) {
    val libsDir = runDirFile.resolve("plugins/hyperzonelogin/libs")
    libsDir.mkdirs()

    runtimeDependencyProjects
        .mapNotNull { it.configurations.findByName("needPackageResolver") }
        .forEach { configuration ->
            configuration.resolvedConfiguration.resolvedArtifacts
                .filter { artifact ->
                    artifact.extension == "jar" && artifact.moduleVersion.id.group != "unspecified"
                }
                .sortedBy { artifact ->
                    val moduleId = artifact.moduleVersion.id
                    moduleId.group + ":" + artifact.name + ":" + moduleId.version
                }
                .forEach { artifact ->
                    val moduleId = artifact.moduleVersion.id
                    val targetFile = libsDir.resolve(cacheFileName(moduleId.group, artifact.name, moduleId.version))
                    artifact.file.copyTo(targetFile, overwrite = true)
                }
        }

    if (overwriteConfigFiles || !velocityToml.asFile.exists()) {
        velocityToml.asFile.writeText(velocityTomlContent())
    }

    val bStatsConfig = runDirFile.resolve("plugins/bStats/config.txt")
    bStatsConfig.parentFile.mkdirs()
    if (overwriteConfigFiles || !bStatsConfig.exists()) {
        bStatsConfig.writeText(bStatsConfigContent())
    }

    val forwardingSecret = runDirFile.resolve("forwarding.secret")
    if (overwriteConfigFiles || !forwardingSecret.exists()) {
        forwardingSecret.writeText("xQHleQQvdFNe\n")
    }

    val startConf = runDirFile.resolve("plugins/hyperzonelogin/start.conf")
    startConf.parentFile.mkdirs()
    if (overwriteConfigFiles || !startConf.exists()) {
        startConf.writeText(startConfContent())
    }
}

val prepareVelocityRun = tasks.register<Sync>("prepareVelocityRun") {
    group = "application"
    description = "Fully resets the local Velocity proxy run directory with the HyperZoneLogin monolith plugin."

    dependsOn(rootProject.tasks.named("collectPluginJars"))

    into(runDir)
    from(rootProject.layout.buildDirectory.dir("HZL")) {
        into("plugins")
    }
    doFirst {
        runDir.asFile.resolve("plugins")
            .listFiles()
            ?.filter { file -> file.isFile && file.name.startsWith("HyperZoneLogin-") && file.name.endsWith(".jar") }
            ?.forEach { file ->
                file.delete()
            }
    }
    doLast {
        val runDirFile = runDir.asFile
        runDirFile.mkdirs()
        stageVelocityRunDirectory(runDirFile, overwriteConfigFiles = true)
    }
}

val stageVelocityRun = tasks.register<Copy>("stageVelocityRun") {
    group = "application"
    description = "Stages the local Velocity proxy run directory without removing existing config files."

    dependsOn(rootProject.tasks.named("collectPluginJars"))

    into(runDir)
    from(rootProject.layout.buildDirectory.dir("HZL")) {
        into("plugins")
    }
    doFirst {
        runDir.asFile.resolve("plugins")
            .listFiles()
            ?.filter { file -> file.isFile && file.name.startsWith("HyperZoneLogin-") && file.name.endsWith(".jar") }
            ?.forEach { file ->
                file.delete()
            }
    }

    doLast {
        val runDirFile = runDir.asFile
        runDirFile.mkdirs()
        stageVelocityRunDirectory(runDirFile, overwriteConfigFiles = false)
    }
}

tasks.register<JavaExec>("runVelocity") {
    group = "application"
    description = "Runs a local Velocity proxy with HyperZoneLogin plugins staged in run/plugins."

    dependsOn(stageVelocityRun)

    classpath = sourceSets.named("main").get().runtimeClasspath
    mainClass.set("com.velocitypowered.proxy.Velocity")
    workingDir = runDir.asFile
    standardInput = System.`in`
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
