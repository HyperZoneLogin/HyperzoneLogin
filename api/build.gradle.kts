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

import org.gradle.api.publish.maven.MavenPublication
import org.gradle.jvm.tasks.Jar

plugins {
    alias(libs.plugins.kotlin)
    id("maven-publish")
}

dependencies {
//    VC
    compileOnly(libs.velocityApi)
    compileOnly(libs.velocityProxy)
    compileOnly(libs.brigadier)
// Limbo is optional; implementations may provide a bridge adapter. Do not
// require the limbo API here at compile time.
    // Netty is needed by API types (connection/player extensions) but only at compile time
    compileOnly(libs.nettyAll)
// Exposed ORM
    implementation(libs.exposedCore)

    testImplementation(libs.velocityApi)
    testImplementation(platform(libs.junitBom))
    testImplementation(libs.junitJupiter)
    testRuntimeOnly(libs.junitPlatformLauncher)
}

tasks.test {
    useJUnitPlatform()
}

// Publish configuration to allow `api` to be published to the local Maven repository
// Use `./gradlew :api:publishToMavenLocal` to publish.
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            groupId = project.group.toString()
            artifactId = "api"
            version = project.version.toString()
            from(components["java"])

            // include sources JAR for better IDE support when consumed from mavenLocal
            val sourcesJar = tasks.register("sourcesJar", Jar::class) {
                archiveClassifier.set("sources")
                from(sourceSets.main.get().allSource)
            }
            artifact(sourcesJar)
        }
    }

    repositories {
        mavenLocal()
    }
}
dependencies { compileOnly(libs.configurateHocon); compileOnly(libs.configurateExtraKotlin) }
