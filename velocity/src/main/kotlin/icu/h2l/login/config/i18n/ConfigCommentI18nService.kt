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

package icu.h2l.login.config.i18n

import icu.h2l.api.util.ConfigCommentTranslator
import net.kyori.adventure.text.logger.slf4j.ComponentLogger
import org.spongepowered.configurate.ConfigurationNode
import org.spongepowered.configurate.hocon.HoconConfigurationLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.LinkedHashSet
import java.util.Locale
import java.util.stream.Collectors

/**
 * 配置文件注释 i18n 服务。
 *
 * 从配置目录中加载配置注释翻译表；
 * 主插件：config-comments/{locale}.conf
 * 子模块：config-comments/{namespace}/{locale}.conf
 *
 * 根据服务端 JVM 区域自动选择语言，在配置首次生成时将翻译键替换为本地化文本。
 *
 * 翻译键格式：config.{模块}.{字段路径}，例如 "config.core.database"。
 */
class ConfigCommentI18nService(
    dataDirectory: Path,
    private val logger: ComponentLogger,
    /**
     * 覆盖语言（来自 defaultLocale 配置），null 时自动检测 JVM 区域。
     */
    private val defaultLocaleOverride: String? = null,
) : ConfigCommentTranslator {

    private val configCommentsDirectory = dataDirectory.resolve(RESOURCE_DIR)

    @Volatile
    private var localeNodes: Map<String, List<ConfigurationNode>> = emptyMap()

    init {
        copyBundledLocalesIfMissing()
        reload()
    }

    /**
     * 重新扫描配置目录中的翻译资源。
     */
    override fun reload() {
        localeNodes = loadLocaleNodes()
    }

    /**
     * 翻译给定的配置注释键，返回本地化文本；键不存在则返回 null（保留原键）。
     */
    override fun translate(key: String): String? {
        if (!key.startsWith("config.")) {
            return null
        }
        for (locale in buildLocaleCandidates()) {
            for (node in localeNodes[locale].orEmpty()) {
                val value = node.node(key).string
                if (!value.isNullOrBlank()) {
                    return value
                }
            }
        }
        return null
    }

    private fun buildLocaleCandidates(): List<String> {
        val result = LinkedHashSet<String>()
        defaultLocaleOverride?.let { normalizeLocale(it)?.let(result::add) }
        normalizeLocale(Locale.getDefault().toLanguageTag())?.let(result::add)
        normalizeLocale(Locale.getDefault().language)?.let(result::add)
        result += DEFAULT_LOCALE
        return result.toList()
    }

    private fun normalizeLocale(raw: String?): String? {
        val normalized = raw
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.replace('-', '_')
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return when (normalized) {
            "en" -> "en_us"
            "zh", "zh_hans", "zh_hans_cn" -> "zh_cn"
            "ru" -> "ru_ru"
            else -> normalized
        }
    }

    private fun loadLocaleNodes(): Map<String, List<ConfigurationNode>> {
        val loaded = linkedMapOf<String, MutableList<ConfigurationNode>>()
        val moduleDirectories = listModuleDirectories()
        BUNDLED_LOCALES.forEach { locale ->
            val resourceFiles = buildList {
                add(configCommentsDirectory.resolve("$locale.conf"))
                moduleDirectories.forEach { directory ->
                    add(directory.resolve("$locale.conf"))
                }
            }
            resourceFiles.forEach { path ->
                loadResource(path)?.let { node ->
                    loaded.getOrPut(locale) { mutableListOf() }.add(node)
                }
            }
        }

        return loaded.mapValues { (_, nodes) -> nodes.toList() }
    }

    private fun copyBundledLocalesIfMissing() {
        runCatching {
            Files.createDirectories(configCommentsDirectory)
            BUNDLED_LOCALES.forEach { locale ->
                val target = configCommentsDirectory.resolve("$locale.conf")
                if (Files.exists(target)) {
                    return@forEach
                }

                val resourcePath = "$RESOURCE_DIR/$locale.conf"
                val resource = javaClass.classLoader.getResourceAsStream(resourcePath)
                if (resource == null) {
                    logger.warn("[ConfigCommentI18n] 未找到内置翻译资源：{}", resourcePath)
                    return@forEach
                }

                resource.use { input ->
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }.onFailure { e ->
            logger.warn("[ConfigCommentI18n] 复制内置翻译资源失败：{}", e.message)
        }
    }

    private fun listModuleDirectories(): List<Path> {
        if (Files.notExists(configCommentsDirectory)) {
            return emptyList()
        }

        return runCatching {
            Files.list(configCommentsDirectory).use { paths ->
                paths
                    .filter { Files.isDirectory(it) }
                    .sorted(Comparator.comparing<Path, String> { it.fileName.toString() })
                    .collect(Collectors.toList())
            }
        }.onFailure { e ->
            logger.warn("[ConfigCommentI18n] 扫描翻译资源目录失败：{}", e.message)
        }.getOrDefault(emptyList())
    }

    private fun loadResource(path: Path): ConfigurationNode? {
        if (Files.notExists(path) || !Files.isRegularFile(path)) {
            return null
        }

        return runCatching {
            HoconConfigurationLoader.builder()
                .path(path)
                .build()
                .load()
        }.onFailure { e ->
            logger.warn("[ConfigCommentI18n] 加载翻译资源失败：{} — {}", path, e.message)
        }.getOrNull()
    }

    companion object {
        private const val RESOURCE_DIR = "config-comments"
        private const val DEFAULT_LOCALE = "en_us"
        private val BUNDLED_LOCALES = listOf("zh_cn", "en_us", "ru_ru")
    }
}
