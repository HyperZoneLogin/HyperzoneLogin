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

package icu.h2l.login.message

import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.proxy.Player
import icu.h2l.api.message.HyperZoneMessagePlaceholder
import icu.h2l.api.message.HyperZoneMessageService
import icu.h2l.api.player.HyperZonePlayer
import icu.h2l.login.config.MessagesConfig
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.logger.slf4j.ComponentLogger
import net.kyori.adventure.text.minimessage.MiniMessage
import org.spongepowered.configurate.ConfigurationNode
import org.spongepowered.configurate.hocon.HoconConfigurationLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.stream.Collectors
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

class MessageService(
    private val dataDirectory: Path,
    private val logger: ComponentLogger
) : HyperZoneMessageService {
    private val miniMessage = MiniMessage.miniMessage()
    private val warnedMissingKeys = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var config: MessagesConfig = MessagesConfig()

    @Volatile
    private var locales: Map<String, ConfigurationNode> = emptyMap()

    fun load(config: MessagesConfig) {
        this.config = config
        ensureMessageDirectory()
        copyBundledLocalesIfMissing()
        locales = loadLocaleNodes()
        warnedMissingKeys.clear()
    }

    override fun reload() {
        load(config)
    }

    fun send(source: CommandSource, key: String, vararg placeholders: HyperZoneMessagePlaceholder) {
        source.sendMessage(render(source, key, *placeholders))
    }

    fun send(player: HyperZonePlayer, key: String, vararg placeholders: HyperZoneMessagePlaceholder) {
        player.sendMessage(render(player, key, *placeholders))
    }

    override fun render(key: String, vararg placeholders: HyperZoneMessagePlaceholder): Component {
        return renderInternal(null as String?, key, *toInternalPlaceholders(placeholders))
    }

    override fun render(source: CommandSource?, key: String, vararg placeholders: HyperZoneMessagePlaceholder): Component {
        val localeKey = (source as? Player)?.let(::resolvePlayerLocaleKey)
        return renderInternal(localeKey, key, *toInternalPlaceholders(placeholders))
    }

    override fun render(player: HyperZonePlayer, key: String, vararg placeholders: HyperZoneMessagePlaceholder): Component {
        return renderInternal(player.getProxyPlayerOrNull()?.let(::resolvePlayerLocaleKey), key, *toInternalPlaceholders(placeholders))
    }

    private fun renderInternal(localeKey: String?, key: String, vararg placeholders: MessagePlaceholder): Component {
        val template = resolveTemplate(localeKey, key)
        return miniMessage.deserialize(template, *placeholders.map { it.asTagResolver() }.toTypedArray())
    }

    internal fun resolveTemplate(localeKey: String?, key: String): String {
        val candidates = LinkedHashSet<String>()
        normalizeLocaleKey(localeKey)?.let(candidates::add)
        normalizeLocaleKey(config.defaultLocale)?.let(candidates::add)
        normalizeLocaleKey(config.fallbackLocale)?.let(candidates::add)
        candidates += DEFAULT_LOCALE

        for (candidate in candidates) {
            val template = findTemplate(candidate, key)
            if (!template.isNullOrBlank()) {
                return template
            }
        }

        val warningKey = "${normalizeLocaleKey(localeKey) ?: "default"}:$key"
        if (warnedMissingKeys.add(warningKey)) {
            logger.warn("Missing i18n message key '{}' for locale chain {}", key, candidates.joinToString(" -> "))
        }
        return "<red>Missing message:</red> <white>$key</white>"
    }

    internal fun normalizeLocaleKey(raw: String?): String? {
        val normalized = raw
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.replace('-', '_')
            ?.takeIf { it.isNotBlank() }
            ?: return null

        return when (normalized) {
            "en" -> "en_us"
            "zh" -> "zh_cn"
            "ru" -> "ru_ru"
            else -> normalized
        }
    }

    private fun ensureMessageDirectory() {
        Files.createDirectories(dataDirectory.resolve(MESSAGES_DIRECTORY))
    }

    private fun copyBundledLocalesIfMissing() {
        for (localeKey in BUNDLED_LOCALES) {
            val target = dataDirectory.resolve(MESSAGES_DIRECTORY).resolve("$localeKey.conf")
            if (Files.exists(target)) {
                continue
            }

            val resourcePath = "$MESSAGES_DIRECTORY/$localeKey.conf"
            val resource = javaClass.classLoader.getResourceAsStream(resourcePath)
            if (resource == null) {
                logger.warn("Bundled locale resource '{}' was not found", resourcePath)
                continue
            }

            resource.use { input ->
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun loadLocaleNodes(): Map<String, ConfigurationNode> {
        val localeDir = dataDirectory.resolve(MESSAGES_DIRECTORY)
        if (Files.notExists(localeDir)) {
            return emptyMap()
        }

        val loaded = linkedMapOf<String, ConfigurationNode>()
        val emptyNodeFactory = HoconConfigurationLoader.builder().build()
        Files.walk(localeDir).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.extension.equals("conf", ignoreCase = true) }
                .sorted(Comparator.comparing<Path, String> { it.toString() })
                .collect(Collectors.toList())
                .forEach { path ->
                    val localeKey = normalizeLocaleKey(path.nameWithoutExtension) ?: return@forEach
                    runCatching {
                        HoconConfigurationLoader.builder()
                            .path(path)
                            .build()
                            .load()
                    }.onSuccess { node ->
                        val mergedNode = loaded.getOrPut(localeKey) { emptyNodeFactory.createNode() }
                        mergedNode.mergeFrom(node)
                    }.onFailure { throwable ->
                        logger.warn("Failed to load locale file '{}': {}", localeDir.relativize(path).toString(), throwable.message)
                    }
                }
        }
        return loaded
    }

    private fun toInternalPlaceholders(placeholders: Array<out HyperZoneMessagePlaceholder>): Array<MessagePlaceholder> {
        return placeholders.map { placeholder ->
            when (placeholder) {
                is HyperZoneMessagePlaceholder.Text -> MessagePlaceholder.text(placeholder.name, placeholder.value)
                is HyperZoneMessagePlaceholder.ComponentValue -> MessagePlaceholder.component(placeholder.name, placeholder.value)
            }
        }.toTypedArray()
    }

    private fun resolvePlayerLocaleKey(player: Player): String? {
        if (!config.useClientLocale) {
            return null
        }

        return normalizeLocaleKey(
            invokeLocaleAccessor(player, "getEffectiveLocale")
                ?: invokeLocaleAccessor(player, "getLocale")
                ?: resolvePlayerSettingsLocale(player)
        )
    }

    private fun resolvePlayerSettingsLocale(player: Player): String? {
        val settings = runCatching {
            player.javaClass.methods.firstOrNull {
                it.name == "getPlayerSettings" && it.parameterCount == 0
            }?.invoke(player)
        }.getOrNull() ?: return null

        return invokeLocaleAccessor(settings, "getLocale")
    }

    private fun invokeLocaleAccessor(target: Any, methodName: String): String? {
        val value = runCatching {
            target.javaClass.methods.firstOrNull {
                it.name == methodName && it.parameterCount == 0
            }?.invoke(target)
        }.getOrNull() ?: return null

        return when (value) {
            is Locale -> value.toLanguageTag()
            else -> value.toString()
        }
    }

    private fun findTemplate(localeKey: String, key: String): String? {
        val node = locales[localeKey] ?: return null
        return node.node(*key.split('.').toTypedArray()).string
    }

    companion object {
        private const val MESSAGES_DIRECTORY = "messages"
        private const val DEFAULT_LOCALE = "en_us"
        private val BUNDLED_LOCALES = listOf("en_us", "zh_cn", "ru_ru")
    }
}


