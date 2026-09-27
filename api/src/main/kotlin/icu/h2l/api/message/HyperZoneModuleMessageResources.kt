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

package icu.h2l.api.message

import icu.h2l.api.util.ConfigCommentTranslatorProvider
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 复制子模块消息和配置注释资源。
 */
object HyperZoneModuleMessageResources {
    /**
     * 将模块 jar 内打包的 locale 文件复制到 `messages/<namespace>` 和 `config-comments/<namespace>` 目录。
     *
     * 已存在的目标文件会被保留，不会覆盖用户自定义内容。
     */
    fun copyBundledLocales(
        dataDirectory: Path,
        namespace: String,
        classLoader: ClassLoader,
        locales: List<String> = listOf("en_us", "zh_cn", "ru_ru")
    ) {
        val messageDirectory = dataDirectory.resolve("messages").resolve(namespace)
        val commentDirectory = dataDirectory.resolve("config-comments").resolve(namespace)
        Files.createDirectories(messageDirectory)
        Files.createDirectories(commentDirectory)

        locales.forEach { localeKey ->
            copyFile(messageDirectory, namespace, classLoader, "messages", localeKey)
            copyFile(commentDirectory, namespace, classLoader, "config-comments", localeKey)
        }

        HyperZoneMessageServiceProvider.getOrNull()?.reload()
        ConfigCommentTranslatorProvider.getOrNull()?.reload()
    }

    private fun copyFile(
        dir: Path,
        namespace: String,
        classLoader: ClassLoader,
        type: String,
        locale: String
    ) {
        val messageTarget = dir.resolve("$locale.conf")
        if (Files.notExists(messageTarget)) {
            val resourcePath = "$type/$namespace/$locale.conf"
            val resource = classLoader.getResourceAsStream(resourcePath)
            resource?.use { input ->
                Files.copy(input, messageTarget, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
