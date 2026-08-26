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

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.shadow)
    application
    kotlin("kapt")
}

dependencies {
    implementation(libs.picocli)
    annotationProcessor(libs.picocliCodegen)
    implementation(libs.gson)
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("icu.h2l.login.cli.MainKt")
}

tasks {
    named<ShadowJar>("shadowJar") {
        archiveBaseName.set("HyperZoneLogin-CLI")
        archiveClassifier.set("all")
        mergeServiceFiles()
        manifest {
            attributes["Main-Class"] = "icu.h2l.login.cli.MainKt"
        }
    }

    named("assemble") {
        dependsOn(named("shadowJar"))
    }
}

repositories {
    mavenCentral()
}