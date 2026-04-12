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

package icu.h2l.login.auth.offline.config

import icu.h2l.api.message.HyperZoneMessageServiceProvider
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

object OfflineAuthMessageResourceLoader {
    private val bundledLocales = listOf("en_us", "zh_cn", "ru_ru")

    fun load(dataDirectory: Path) {
        val messageDir = dataDirectory.resolve("messages").resolve("auth-offline")
        Files.createDirectories(messageDir)

        bundledLocales.forEach { localeKey ->
            val target = messageDir.resolve("$localeKey.conf")
            if (Files.exists(target)) {
                return@forEach
            }

            val resourcePath = "messages/auth-offline/$localeKey.conf"
            val resource = javaClass.classLoader.getResourceAsStream(resourcePath) ?: return@forEach
            resource.use { input ->
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }

        HyperZoneMessageServiceProvider.getOrNull()?.reload()
    }
}

