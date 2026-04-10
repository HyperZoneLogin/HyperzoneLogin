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
import org.gradle.jvm.tasks.Jar

plugins {
    base
    alias(libs.plugins.spotless)
    alias(libs.plugins.kotlin) apply false
}

fun toBlockCommentHeader(headerFile: File): String {
    val body = headerFile
        .readLines()
        .dropLastWhile { it.isBlank() }
        .joinToString("\n") { line -> if (line.isBlank()) " *" else " * $line" }

    return "/*\n$body\n *\n */\n\n"
}

val kotlinLicenseHeader = toBlockCommentHeader(rootProject.file("HEADER.txt"))
val kotlinSourceHeaderDelimiter = "^(package|@file:|import)"
val kotlinGradleHeaderDelimiter = "^(import|plugins|buildscript|pluginManagement|dependencyResolutionManagement|rootProject|include)"

spotless {
    kotlin {
        target(
            "api/src/**/*.kt",
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

    repositories {
        maven("https://maven.aliyun.com/repository/central")
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        mavenCentral()

        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://maven.fabricmc.net/")
        maven("https://maven.elytrium.net/repo/")
    }
}

val pluginBundleDir = layout.buildDirectory.dir("HZL")

val collectPluginJars by tasks.registering(Sync::class) {
    group = "build"
    description = "Collects all non-API plugin jars into one directory, embedding the core plugin as HyperZoneLogin and prefixing optional modules with HZL-."
    into(pluginBundleDir)

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

tasks.named("assemble") {
    dependsOn(collectPluginJars)
}

tasks.named("check") {
    dependsOn(tasks.named("spotlessCheck"))
}

tasks.named("build") {
    dependsOn(collectPluginJars)
}
