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

pluginManagement {
    repositories {
        val isCi = System.getenv("CI") == "true"
        if (!isCi) {
            maven("https://maven.aliyun.com/repository/central")
            maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        }
        maven("https://plugins.gradle.org/m2/")
        gradlePluginPortal()
        mavenCentral()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "HyperzoneLogin"

include("velocity")
include("vc-runtest")
include("api")
include("auth-floodgate")
include("auth-yggd")
include("auth-offline")
include("safe")
include("data-merge")
include("profile-skin")
