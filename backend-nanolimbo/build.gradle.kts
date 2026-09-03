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
    alias(libs.plugins.kotlinLombok)
}

sourceSets {
    main {
        java.srcDir("NanoLimbo/src/main/java")
        resources.srcDir("NanoLimbo/src/main/resources")
    }
}

dependencies {
    compileOnly(project(":api"))
    compileOnly(libs.velocityApi)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    compileOnly(libs.configurateYaml)
    compileOnly(libs.nettyAll)
    compileOnly(libs.adventureApi)
    compileOnly(libs.adventureTextSerializerGson)
    compileOnly(libs.adventureTextSerializerLegacy)
    compileOnly(libs.adventureTextSerializerJsonLegacyImpl)
    compileOnly(libs.adventureTextSerializerPlain)
    compileOnly(libs.adventureTextMinimessage)
    compileOnly(libs.adventureNbt)
    compileOnly(libs.gson)
    testImplementation(kotlin("test"))
}

tasks.named<Jar>("jar") {
    archiveClassifier.set("")
    archiveVersion.set("")
    manifest {
        attributes(mapOf("Main-Class" to "icu.h2l.login.nanolimbo.NanoLimboLauncher"))
    }
}


repositories {
    mavenCentral()
}