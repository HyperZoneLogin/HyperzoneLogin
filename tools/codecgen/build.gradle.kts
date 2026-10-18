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
    application
}

application {
    mainClass.set("icu.h2l.login.codecgen.CodecGenMainKt")
}

dependencies {
    // Remapping the obfuscated official server.jar back to Mojang-mapped names.
    implementation(libs.asm)
    implementation(libs.asmCommons)
    // JSON parsing for the Mojang version manifest.
    implementation(libs.gson)
}

tasks.jar {
    // Bundle the bundled CodecDump.java harness source so it can be compiled at runtime.
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
}
