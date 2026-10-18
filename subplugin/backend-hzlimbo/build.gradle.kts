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

plugins {
    alias(libs.plugins.kotlin)
}

dependencies {
    // The API is provided by the main plugin at runtime.
    compileOnly(project(":api"))
    // Reuse the Velocity proxy's internal networking stack (MinecraftConnection, StateRegistry,
    // packet codecs, Netty handlers) that is already present on the host Velocity classpath.
    compileOnly(libs.velocityApi)
    compileOnly(libs.velocityProxy)
    compileOnly(libs.nettyAll)
    compileOnly(libs.adventureApi)
    compileOnly(libs.adventureNbt)
    compileOnly(libs.brigadier)

    testImplementation(kotlin("test"))
}

tasks.named<Jar>("jar") {
    archiveClassifier.set("")
    archiveVersion.set("")
}
