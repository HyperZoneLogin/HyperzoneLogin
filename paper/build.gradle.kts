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
    alias(libs.plugins.shadow)
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.24"
}

dependencies {
    paperweight.paperDevBundle("26.3.build.+")
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
    // 仅用于把导出的 NBT 格式化成可读的 SNBT（Paper 本身不提供 adventure-nbt）。
    implementation(libs.adventureNbt)
}

tasks.shadowJar {
    archiveClassifier.set("")
    // Paper 服务端不提供 Kotlin 运行时，重定位打包进插件，避免与其他插件冲突。
    relocate("kotlin", "icu.h2l.login.libs.kotlin")
    // Paper 服务端不提供 adventure-nbt，同样重定位隔离。
    relocate("net.kyori.adventure.nbt", "icu.h2l.login.libs.adventure.nbt")
    relocate("net.kyori.examination", "icu.h2l.login.libs.examination")
    minimize()
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
