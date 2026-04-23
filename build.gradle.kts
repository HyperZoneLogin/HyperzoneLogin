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

import org.gradle.jvm.tasks.Jar
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.WeekFields

plugins {
    base
    alias(libs.plugins.spotless)
    alias(libs.plugins.kotlin) apply false
}

enum class ReleaseChannel(val suffix: String) {
    STABLE(""),
    RC("-RC"),
    WEEKLY("-WEEKLY"),
    SNAPSHOT("-SNAPSHOT"),
}

fun toBlockCommentHeader(headerFile: File): String {
    val body = headerFile
        .readLines()
        .dropLastWhile { it.isBlank() }
        .joinToString("\n") { line -> if (line.isBlank()) " *" else " * $line" }

    return "/*\n$body\n *\n */\n\n"
}

fun Project.requireStringProperty(name: String): String =
    findProperty(name)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        ?: error("Missing required Gradle property '$name'.")

fun Project.gitDir(): File? {
    val dotGit = rootDir.resolve(".git")
    return when {
        dotGit.isDirectory -> dotGit
        dotGit.isFile -> {
            val pointer = dotGit.readText().trim()
            val prefix = "gitdir:"
            if (!pointer.startsWith(prefix, ignoreCase = true)) {
                null
            } else {
                rootDir.resolve(pointer.substring(prefix.length).trim()).normalize()
            }
        }

        else -> null
    }
}

fun readPackedRef(gitDir: File, refPath: String): String? {
    val packedRefs = gitDir.resolve("packed-refs")
    if (!packedRefs.isFile) {
        return null
    }

    return packedRefs.useLines { lines ->
        lines
            .filter { line -> line.isNotBlank() && !line.startsWith("#") && !line.startsWith("^") }
            .mapNotNull { line ->
                val parts = line.trim().split(' ', limit = 2)
                if (parts.size == 2 && parts[1] == refPath) parts[0] else null
            }
            .firstOrNull()
    }
}

fun Project.resolveGitCommitId(): String? {
    val override = findProperty("versionGitOverride")?.toString()?.trim()
    if (!override.isNullOrBlank()) {
        return override
    }

    val environmentValue = sequenceOf(
        "HZL_GIT_ID",
        "GITHUB_SHA",
        "CI_COMMIT_SHA",
        "BUILD_VCS_NUMBER",
        "GIT_COMMIT",
    ).mapNotNull { System.getenv(it)?.trim()?.takeIf(String::isNotBlank) }.firstOrNull()
    if (environmentValue != null) {
        return environmentValue
    }

    val gitDir = gitDir() ?: return null
    val headFile = gitDir.resolve("HEAD")
    if (!headFile.isFile) {
        return null
    }

    val head = headFile.readText().trim()
    return if (head.startsWith("ref:")) {
        val refPath = head.removePrefix("ref:").trim()
        val refFile = gitDir.resolve(refPath)
        when {
            refFile.isFile -> refFile.readText().trim()
            else -> readPackedRef(gitDir, refPath)
        }
    } else {
        head.takeIf { it.isNotBlank() }
    }
}

fun normalizeGitIdentifier(raw: String?): String? = raw
    ?.trim()
    ?.removePrefix("refs/heads/")
    ?.ifBlank { null }
    ?.let { value -> if (value.length > 8) value.take(8) else value }

fun formatBaseVersion(
    releaseChannel: ReleaseChannel,
    versionPatch: Int,
    monthlyClock: YearMonth,
    weeklyClock: LocalDate,
): String = when (releaseChannel) {
    ReleaseChannel.WEEKLY -> {
        val isoWeekFields = WeekFields.ISO
        val weekBasedYear = weeklyClock.get(isoWeekFields.weekBasedYear()) % 100
        val weekOfYear = weeklyClock.get(isoWeekFields.weekOfWeekBasedYear())
        "%02d.W%02d.%d".format(weekBasedYear, weekOfYear, versionPatch)
    }

    else -> "${monthlyClock.year % 100}.${monthlyClock.monthValue}.$versionPatch"
}

val versionClock: YearMonth = YearMonth.now()
val weeklyVersionClock: LocalDate = LocalDate.now()
val versionPatch = requireStringProperty("versionPatch").toIntOrNull()
    ?.takeIf { it > 0 }
    ?: error("Property 'versionPatch' must be a positive integer.")
val releaseChannel = runCatching {
    ReleaseChannel.valueOf(requireStringProperty("releaseChannel").uppercase())
}.getOrElse {
    error("Property 'releaseChannel' must be one of: ${ReleaseChannel.entries.joinToString()}.")
}
val baseVersion = formatBaseVersion(releaseChannel, versionPatch, versionClock, weeklyVersionClock)
val gitIdentifier = normalizeGitIdentifier(resolveGitCommitId())
val computedVersion = buildString {
    append(baseVersion)
    append(releaseChannel.suffix)
    if (gitIdentifier != null) {
        append('-')
        append(gitIdentifier)
    }
}

version = computedVersion

val kotlinLicenseHeader = toBlockCommentHeader(rootProject.file("HEADER.txt"))
val kotlinSourceHeaderDelimiter = "^(package|@file:|import)"
val kotlinGradleHeaderDelimiter = "^(import|plugins|buildscript|pluginManagement|dependencyResolutionManagement|rootProject|include)"

spotless {
    kotlin {
        target(
            "api/src/**/*.kt",
            "auth-floodgate/src/**/*.kt",
            "auth-offline/src/**/*.kt",
            "auth-yggd/src/**/*.kt",
            "data-merge/src/**/*.kt",
            "profile-skin/src/**/*.kt",
            "safe/src/**/*.kt",
            "velocity/src/**/*.kt",
        )
        licenseHeader(kotlinLicenseHeader, kotlinSourceHeaderDelimiter)
    }

    kotlinGradle {
        target(
            "build.gradle.kts",
            "settings.gradle.kts",
            "api/build.gradle.kts",
            "auth-floodgate/build.gradle.kts",
            "auth-offline/build.gradle.kts",
            "auth-yggd/build.gradle.kts",
            "data-merge/build.gradle.kts",
            "profile-skin/build.gradle.kts",
            "safe/build.gradle.kts",
            "velocity/build.gradle.kts",
        )
        licenseHeader(kotlinLicenseHeader, kotlinGradleHeaderDelimiter)
    }
}

subprojects {
    group = rootProject.group
    version = rootProject.version

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(21)
        }
    }

    repositories {
        maven("https://maven.aliyun.com/repository/central")
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        mavenCentral()

        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://maven.fabricmc.net/")
        maven("https://maven.elytrium.net/repo/")
        maven("https://repo.opencollab.dev/maven-snapshots")
    }

    tasks.withType(ProcessResources::class.java).configureEach {
        val pluginVersion = rootProject.version.toString()
        inputs.property("pluginVersion", pluginVersion)
        filteringCharset = "UTF-8"
        filesMatching("velocity-plugin.json") {
            expand("pluginVersion" to pluginVersion)
        }
    }
}

val pluginBundleDir = layout.buildDirectory.dir("HZL")
val splitPluginBundleDir = layout.buildDirectory.dir("HZL-split")

val collectPluginJars by tasks.registering(Sync::class) {
    group = "build"
    description = "Collects the all-in-one HyperZoneLogin jar into one distribution directory."
    into(pluginBundleDir)

    val velocityProject = project(":velocity")
    dependsOn(velocityProject.tasks.named("monolithJar"))
    from(velocityProject.tasks.named("monolithJar", Jar::class).flatMap { it.archiveFile })
}

val collectSplitPluginJars by tasks.registering(Sync::class) {
    group = "build"
    description = "Collects the split plugin distribution with the main plugin and optional module jars."
    into(splitPluginBundleDir)

    val velocityProject = project(":velocity")
    dependsOn(velocityProject.tasks.named("jar"))
    from(velocityProject.tasks.named("jar", Jar::class).flatMap { it.archiveFile })

    subprojects
        .filter { it.path != ":api" && it.path != ":velocity" }
        .forEach { subproject ->
            val archiveTaskName = "jar"
            dependsOn(subproject.tasks.named(archiveTaskName))
            from(subproject.tasks.named(archiveTaskName, Jar::class).flatMap { it.archiveFile }) {
                rename { fileName ->
                    if (fileName.startsWith("HZL-")) fileName else "HZL-$fileName"
                }
            }
        }
}

val buildMonolith by tasks.registering {
    group = "build"
    description = "Builds the all-in-one HyperZoneLogin distribution."
    dependsOn(collectPluginJars)
}

val buildAllDistributions by tasks.registering {
    group = "build"
    description = "Builds both the all-in-one and split HyperZoneLogin distributions."
    dependsOn(collectPluginJars)
    dependsOn(collectSplitPluginJars)
}

val printVersionInfo by tasks.registering {
    group = "help"
    description = "Prints the resolved HyperZoneLogin version components."
    doLast {
        println("HyperZoneLogin version: $computedVersion")
        println("  baseVersion    = $baseVersion")
        println("  releaseChannel = ${releaseChannel.name}")
        println("  gitIdentifier  = ${gitIdentifier ?: "<none>"}")
    }
}

tasks.named("assemble") {
    dependsOn(collectPluginJars)
}

tasks.named("check") {
    dependsOn(tasks.named("spotlessCheck"))
}

tasks.named("build") {
    dependsOn(collectPluginJars)
}
