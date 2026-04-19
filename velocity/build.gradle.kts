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
import icu.h2l.gradle.needPackageCompileOnly
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.jvm.tasks.Jar

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.shadow)
    id("icu.h2l.runtime-dependencies")
}

val bstatsRelocatedClasspath by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

val embeddedModuleProjects = listOf(
    project(":auth-floodgate"),
    project(":auth-offline"),
    project(":auth-yggd"),
    project(":data-merge"),
    project(":safe"),
    project(":profile-skin"),
)

val relocateBstatsCompileOnlyJar by tasks.registering(ShadowJar::class) {
    archiveBaseName.set("bstats-relocated-compileonly")
    archiveClassifier.set("")
    archiveVersion.set("")
    destinationDirectory.set(layout.projectDirectory.dir(".gradle/hzl/compile-only"))
    configurations = listOf(bstatsRelocatedClasspath)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    relocate("org.bstats", "icu.h2l.login.libs.bstats")

    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/INDEX.LIST", "module-info.class")
}

dependencies {
    // Modules (auth-floodgate, auth-offline, auth-yggd, data-merge) are now separate Velocity plugins
    // and will register themselves with the main plugin at runtime. Do not include
    // them as project dependencies here so they are not bundled into the main plugin jar.
    implementation(project(":api"))
//    implementation(project(":vcinjector"))

// Exposed ORM / runtime-loaded libraries
    needPackageCompileOnly(libs.exposedCore)
    needPackageCompileOnly(libs.exposedJdbc)
// Database drivers / runtime-loaded libraries
    needPackageCompileOnly(libs.sqliteJdbc)
    needPackageCompileOnly(libs.mysql)
    needPackageCompileOnly(libs.mariadb)
    needPackageCompileOnly(libs.hikari)
//    VC
    compileOnly(libs.velocityApi)
    compileOnly(libs.velocityProxy) // From Elytrium Repo.
    compileOnly(libs.floodgateApi)
    add(bstatsRelocatedClasspath.name, libs.bstatsVelocity)
    compileOnly(files(relocateBstatsCompileOnlyJar.flatMap { it.archiveFile }))
    needPackageCompileOnly(libs.bstatsVelocity)
    needPackageCompileOnly(libs.jarRelocator)

    needPackageCompileOnly(libs.configurateExtraKotlin)
    needPackageCompileOnly(libs.configurateHocon)
//    for subModule
    needPackageCompileOnly(libs.angusMail)
    needPackageCompileOnly(libs.googleAuth)
    needPackageCompileOnly(libs.h2)
//    ====
    compileOnly(libs.nettyAll)
    compileOnly(libs.gson)
    compileOnly(libs.log4jApi)
    compileOnly(libs.adventureTextSerializerGson)
    compileOnly(libs.adventureTextLoggerSlf4j)
    compileOnly(libs.adventureTextMinimessage)
    needPackageCompileOnly(libs.adventureTextMinimessage)
    compileOnly(libs.guice)
    compileOnly(libs.guava)
    compileOnly(libs.brigadier)

    annotationProcessor(libs.velocityApi)

    testImplementation(platform(libs.junitBom))
    testImplementation(libs.junitJupiter)
    testImplementation(libs.velocityApi)
    testImplementation(libs.velocityProxy)
    testImplementation(libs.nettyAll)
    testImplementation(libs.adventureTextLoggerSlf4j)
    testImplementation(libs.adventureTextMinimessage)
    testImplementation(libs.configurateExtraKotlin)
    testImplementation(libs.configurateHocon)
    testImplementation("io.mockk:mockk:1.13.17")
    testRuntimeOnly(libs.junitPlatformLauncher)
}

tasks {
    named("compileJava") {
        dependsOn(relocateBstatsCompileOnlyJar)
    }

    named("compileKotlin") {
        dependsOn(relocateBstatsCompileOnlyJar)
    }

    named<Jar>("jar") {
        archiveBaseName.set("HyperZoneLogin")
        archiveClassifier.set("")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        val apiProject = project(":api")
        val apiSourceSets = apiProject.extensions.getByType(SourceSetContainer::class.java)
        dependsOn(apiProject.tasks.named("classes"))
        from(apiSourceSets.named("main").get().output)
    }

    register<Jar>("monolithJar") {
        group = "build"
        description = "Builds an all-in-one HyperZoneLogin jar with embedded optional modules."
        archiveBaseName.set("HyperZoneLogin")
        archiveClassifier.set("all")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        val currentSourceSets = project.extensions.getByType(SourceSetContainer::class.java)
        from(currentSourceSets.named("main").get().output)

        val apiProject = project(":api")
        val apiSourceSets = apiProject.extensions.getByType(SourceSetContainer::class.java)
        dependsOn(apiProject.tasks.named("classes"))
        from(apiSourceSets.named("main").get().output)

        embeddedModuleProjects.forEach { embeddedProject ->
            val embeddedSourceSets = embeddedProject.extensions.getByType(SourceSetContainer::class.java)
            dependsOn(embeddedProject.tasks.named("classes"))
            from(embeddedSourceSets.named("main").get().output) {
                exclude("velocity-plugin.json")
                exclude("META-INF/hzl/runtime-dependencies.properties")
            }
        }
    }

    named<ShadowJar>("shadowJar") {
        enabled = false
    }

    test {
        useJUnitPlatform()
    }

    named("assemble") {
        dependsOn(named("jar"))
        dependsOn(named("monolithJar"))
    }
}
