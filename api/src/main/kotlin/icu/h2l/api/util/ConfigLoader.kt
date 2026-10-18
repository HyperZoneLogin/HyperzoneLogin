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

package icu.h2l.api.util

import icu.h2l.api.log.warn
import org.spongepowered.configurate.CommentedConfigurationNode
import org.spongepowered.configurate.ConfigurationNode
import org.spongepowered.configurate.ConfigurationOptions
import org.spongepowered.configurate.hocon.HoconConfigurationLoader
import org.spongepowered.configurate.kotlin.dataClassFieldDiscoverer
import org.spongepowered.configurate.objectmapping.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path



object ConfigLoader {
    /**
     * Unified HOCON configuration loader.
     * @param dataDirectory The directory containing the configuration file
     * @param fileName The name of the configuration file (e.g., "remap.conf")
     * @param nodePath The path within the configuration file. Use an array of keys for nested structures.
     * @param defaultProvider Provides the default config object if reading fails or file empty
     * @param postLoadHook A hook that gets invoked immediately after loading/getting the config. Returning a non-null instance overrides the default/loaded result.
     * @param forceSaveHook Whether to save the file back, usually true if first creation, but can be customized
     */
    inline fun <reified T : Any> loadConfig(
        dataDirectory: Path,
        fileName: String,
        nodePath: Array<String> = emptyArray(),
        defaultProvider: () -> T,
        noinline postLoadHook: ((ConfigurationNode, T, Boolean) -> T)? = null,
        noinline forceSaveHook: ((ConfigurationNode, Boolean) -> Boolean) = { _, firstCreation -> firstCreation }
    ): T {
        val path = dataDirectory.resolve(fileName)
        val fileNotExists = Files.notExists(path)
        val loader = buildLoader(path)
        val node = loader.load()
        val targetNode = if (nodePath.isEmpty()) node else node.node(*nodePath)

        val firstCreation = fileNotExists || targetNode.virtual()

        val loaded = runCatching { targetNode.get(T::class.java) }.getOrNull() ?: defaultProvider()

        // Detect schema mismatch: compare actual file keys with expected keys from a freshly serialised default instance
        if (!targetNode.virtual() && targetNode.isMap) {
            runCatching {
                // 期望的 schema 树：序列化默认对象（含默认值字段）
                val expectedNode = loader.createNode()
                expectedNode.set(T::class.java, defaultProvider())

                // 文件里实际存在的树：重新加载原始文件，且不拷贝默认值，避免缺失键被默认值掩盖
                val rawNode = buildLoader(path, copyDefaults = false).load()
                val actualNode = if (nodePath.isEmpty()) rawNode else rawNode.node(*nodePath)

                val missingKeys = mutableListOf<String>()
                val extraKeys = mutableListOf<String>()
                collectSchemaMismatch(expectedNode, actualNode, "", missingKeys, extraKeys)

                if (extraKeys.isNotEmpty() || missingKeys.isNotEmpty()) {
                    warn {
                        buildString {
                            append("[ConfigLoader] 检测到 \"$fileName\" 与当前版本 schema 不一致")
                            if (nodePath.isNotEmpty()) append(" (node: ${nodePath.joinToString(".")})")
                            append("。请运行 /hzl config regenerate 一键修复")
                            append("（保留旧值、删除多余项、补齐缺失默认值）。")
                            if (extraKeys.isNotEmpty()) append(" 多余键: $extraKeys.")
                            if (missingKeys.isNotEmpty()) append(" 缺失键: $missingKeys.")
                        }
                    }
                }
            }
        }

        val finalConfig = postLoadHook?.invoke(targetNode, loaded, firstCreation) ?: loaded
        val shouldSave = forceSaveHook(targetNode, firstCreation)

        if (shouldSave) {
            targetNode.set(finalConfig)
            val commentedNode = node as? CommentedConfigurationNode
            if (commentedNode != null) {
                ConfigCommentTranslatorProvider.getOrNull()?.let { translator ->
                    translateNodeComments(commentedNode, translator)
                }
            }
            loader.save(node)
        }
        return finalConfig
    }

    /**
     * 根据 [path] 构建 HOCON 的 ConfigurationLoader。
     *
     * 注册 Kotlin data-class 字段发现器，确保 ObjectMapper 正常工作。
     *
     * @param copyDefaults 序列化时是否把默认值写回节点；schema 比对等场景需要关掉它以读取文件真实键。
     */
    @PublishedApi
    internal fun buildLoader(
        path: Path,
        copyDefaults: Boolean = true,
    ): org.spongepowered.configurate.loader.AbstractConfigurationLoader<*> {
        val objectMapperFactory = ObjectMapper.factoryBuilder()
            .addDiscoverer(dataClassFieldDiscoverer())
            .build()

        return HoconConfigurationLoader.builder()
            .defaultOptions { opts: ConfigurationOptions ->
                opts.shouldCopyDefaults(copyDefaults)
                    .serializers { s -> s.registerAnnotatedObjects(objectMapperFactory) }
            }
            .path(path)
            .build()
    }

    @PublishedApi
    internal fun translateNodeComments(node: CommentedConfigurationNode, translator: ConfigCommentTranslator) {
        val comment = node.comment()
        if (comment != null) {
            val translated = translator.translate(comment.trim())
            if (translated != null) {
                node.comment(translated)
            }
        }
        if (node.isMap) {
            node.childrenMap().values.forEach { child -> translateNodeComments(child, translator) }
        } else if (node.isList) {
            node.childrenList().forEach { child -> translateNodeComments(child, translator) }
        }
    }

    /**
     * 递归比对期望树与实际树的键，收集缺失键与多余键（含嵌套层级，路径以 `.` 连接）。
     */
    @PublishedApi
    internal fun collectSchemaMismatch(
        expected: ConfigurationNode,
        actual: ConfigurationNode,
        path: String,
        missing: MutableList<String>,
        extra: MutableList<String>,
    ) {
        if (!expected.isMap || !actual.isMap) return

        val expectedChildren = expected.childrenMap()
        val actualChildren = actual.childrenMap()

        expectedChildren.forEach { (key, _) ->
            if (!actualChildren.containsKey(key)) {
                missing.add(joinKeyPath(path, key.toString()))
            }
        }
        actualChildren.forEach { (key, _) ->
            if (!expectedChildren.containsKey(key)) {
                extra.add(joinKeyPath(path, key.toString()))
            }
        }

        expectedChildren.forEach { (key, expectedChild) ->
            val actualChild = actualChildren[key] ?: return@forEach
            if (expectedChild.isMap && actualChild.isMap) {
                collectSchemaMismatch(expectedChild, actualChild, joinKeyPath(path, key.toString()), missing, extra)
            }
        }
    }

    @PublishedApi
    internal fun joinKeyPath(parent: String, key: String): String =
        if (parent.isEmpty()) key else "$parent.$key"
}
