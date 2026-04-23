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

plugins {
    alias(libs.plugins.kotlin)
}

dependencies {
    // Build as standalone plugin; api is provided at runtime by the main plugin
    compileOnly(project(":api"))
//    VC
    compileOnly(libs.velocityApi)
    compileOnly(libs.velocityProxy) // From Elytrium Repo.
    compileOnly(libs.nettyAll)
// Exposed ORM
    compileOnly(libs.exposedCore)
//    config
    compileOnly(libs.configurateHocon)
    compileOnly(libs.configurateExtraKotlin)

    testImplementation(platform(libs.junitBom))
    testImplementation(libs.junitJupiter)
    testImplementation(project(":api"))
    testImplementation(libs.velocityApi)
    testImplementation(libs.velocityProxy)
    testImplementation(libs.exposedCore)
    testImplementation(libs.configurateHocon)
    testImplementation(libs.configurateExtraKotlin)
    testRuntimeOnly(libs.junitPlatformLauncher)
}

tasks.test {
    useJUnitPlatform()
    // JVM toolchain is pinned to 21; --enable-native-access is supported from Java 21 onwards.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    testLogging {
        showStandardStreams = false
    }
    reports.junitXml.apply {
        includeSystemOutLog = false
        includeSystemErrLog = false
    }
}