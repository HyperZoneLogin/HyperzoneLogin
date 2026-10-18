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
    // The API is provided by the main plugin at runtime. Use compileOnly so
    // the submodule is built as a standalone Velocity plugin and doesn't
    // bundle the API classes (avoids duplication in the main shadow jar).
    compileOnly(project(":api"))
//    VC
    compileOnly(libs.velocityApi)
    compileOnly(libs.brigadier)
    // Exposed ORM
    compileOnly(libs.exposedCore)
//    config
    compileOnly(libs.configurateHocon)
    compileOnly(libs.configurateExtraKotlin)
    needPackageCompileOnly(libs.angusMail)
    needPackageCompileOnly(libs.googleAuth)

    testImplementation(platform(libs.junitBom))
    testImplementation(libs.junitJupiter)
    testImplementation(project(":api"))
    testImplementation(libs.velocityApi)
    testImplementation(libs.nettyAll)
    testImplementation(libs.exposedCore)
    testImplementation(libs.exposedJdbc)
    testImplementation(libs.h2)
    testImplementation(libs.configurateHocon)
    testImplementation(libs.configurateExtraKotlin)
    testImplementation(libs.googleAuth)
    testImplementation("io.mockk:mockk:1.13.17")
    testRuntimeOnly(libs.junitPlatformLauncher)
}

tasks.test {
    useJUnitPlatform()
}