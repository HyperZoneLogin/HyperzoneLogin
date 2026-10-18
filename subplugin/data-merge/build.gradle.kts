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

import icu.h2l.gradle.needPackageCompileOnly

plugins {
    alias(libs.plugins.kotlin)
    id("icu.h2l.runtime-dependencies")
}

dependencies {
    // Build as a standalone Velocity plugin; reference API at compile time only
    compileOnly(project(":api"))
    // The auth modules are separate plugins; keep compileOnly if you reference them
    compileOnly(project(":subplugin:auth-yggd"))
    compileOnly(project(":subplugin:auth-offline"))
    compileOnly(project(":subplugin:auth-floodgate"))

    compileOnly(libs.velocityApi)
    compileOnly(libs.brigadier)

    needPackageCompileOnly(libs.h2)

    compileOnly(libs.exposedCore)
    compileOnly(libs.exposedJdbc)

    compileOnly(libs.configurateHocon)
    compileOnly(libs.configurateExtraKotlin)

    testImplementation(platform(libs.junitBom))
    testImplementation(libs.junitJupiter)
    testRuntimeOnly(libs.junitPlatformLauncher)
}

tasks.test {
    useJUnitPlatform()
}

