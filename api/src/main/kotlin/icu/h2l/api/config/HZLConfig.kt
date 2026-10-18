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

package icu.h2l.api.config

import icu.h2l.api.util.ConfigCommentTranslatorProvider
import icu.h2l.api.util.ConfigLoader
import org.spongepowered.configurate.CommentedConfigurationNode
import org.spongepowered.configurate.ConfigurationNode
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * 一个配置文件（或配置段）的统一访问抽象。
 *
 * 通过 `@Comment("config.xxx.yyy")` 里的 **Comment id** 来定位、读取与修改对应配置项，
 * 使模块之间能够自由获取和修改彼此的配置，而无需依赖对方模块的类。
 *
 * ── 关键设计 ─────────────────────────────────────────────────────────────
 * - **映射只构建一次**：构造时把默认对象序列化成配置树，遍历树上的注释，得到
 *   `Comment id → 配置键路径` 的映射并缓存；后续读写都走缓存，不再反射；
 * - **值缓存**：所有配置项的值缓存在一张 [ConcurrentHashMap] 里，[get] 直接读缓存；
 * - **双向同步**：配置变化时调用 [reload] 刷新缓存；通过 [set] 修改则写回文件并刷新。
 *
 * 说明：注释（`@Comment`）只在 HOCON 这类支持注释的格式下才会被保留，
 * 因此 Comment id 映射依赖 HOCON（项目默认格式）。
 *
 * @param dataDirectory 插件数据目录
 * @param fileName 配置文件名（如 `"core.conf"`）
 * @param defaultProvider 默认配置对象，用于播种与构建映射
 */
abstract class HZLConfig<T : Any>(
    private val dataDirectory: Path,
    private val fileName: String,
    private val defaultProvider: () -> T,
) {
    /** `Comment id → 配置键路径` 的缓存映射，构造时构建一次。 */
    private val keyPaths: Map<String, List<String>> = buildKeyPaths()

    /** `Comment id → 当前值` 的值缓存。 */
    private val cache = ConcurrentHashMap<String, Any?>()

    @Volatile
    private var rootNode: ConfigurationNode? = null

    /**
     * 重新从磁盘加载配置并刷新值缓存。
     *
     * 配置所属方在自身配置发生改变（例如 `/hzl reload`）后应调用本方法，
     * 以便其他模块通过 [get] 读到最新值。
     */
    fun reload() {
        val loader = ConfigLoader.buildLoader(dataDirectory.resolve(fileName))
        rootNode = loader.load()
        refreshCache()
    }

    /**
     * 按 Comment id 读取配置值。
     *
     * @return 标量字段返回对应标量值；对象/列表字段返回原始节点值。未知 id 返回 null。
     */
    fun get(commentId: String): Any? = cache[commentId]

    /**
     * 按 Comment id 修改配置值并写回文件，随后刷新缓存。
     */
    fun set(commentId: String, value: Any?) {
        val keyPath = keyPaths[commentId]
            ?: throw IllegalArgumentException("未知 Comment id '$commentId'（配置文件 $fileName）")
        val loader = ConfigLoader.buildLoader(dataDirectory.resolve(fileName))
        val node = loader.load()
        node.node(*keyPath.toTypedArray()).set(value)
        (node as? CommentedConfigurationNode)?.let { commented ->
            ConfigCommentTranslatorProvider.getOrNull()?.let { translator ->
                ConfigLoader.translateNodeComments(commented, translator)
            }
        }
        loader.save(node)
        reload()
    }

    /** 是否包含给定 Comment id。 */
    fun contains(commentId: String): Boolean = commentId in keyPaths

    /**
     * 一键重新生成配置文件：读取旧配置、保留仍存在的键的旧值、删除多余键、补齐缺失键的默认值。
     *
     * 用于修复旧配置文件与当前代码 schema 不一致（缺失/多余字段）的问题。
     */
    fun regenerate() {
        val path = dataDirectory.resolve(fileName)
        val loader = ConfigLoader.buildLoader(path)
        val oldNode = loader.load()

        // 以默认对象为骨架生成新树（含全部默认值与注释），再把旧值合并回去。
        val newNode = loader.createNode()
        newNode.set(defaultProvider())
        mergeOldValues(newNode, oldNode)

        (newNode as? CommentedConfigurationNode)?.let { commented ->
            ConfigCommentTranslatorProvider.getOrNull()?.let { translator ->
                ConfigLoader.translateNodeComments(commented, translator)
            }
        }

        loader.save(newNode)
        reload()
    }

    private fun refreshCache() {
        val node = rootNode ?: return
        keyPaths.forEach { (commentId, keyPath) ->
            val value = node.node(*keyPath.toTypedArray()).raw()
            if (value != null) {
                cache[commentId] = value
            } else {
                cache.remove(commentId)
            }
        }
    }

    private fun buildKeyPaths(): Map<String, List<String>> {
        val loader = ConfigLoader.buildLoader(dataDirectory.resolve(fileName))
        val node = loader.createNode()
        node.set(defaultProvider())
        val result = mutableMapOf<String, List<String>>()
        collectComments(node, emptyList(), result)
        return result
    }

    private fun collectComments(node: ConfigurationNode, path: List<String>, result: MutableMap<String, List<String>>) {
        (node as? CommentedConfigurationNode)?.comment()?.trim()?.takeIf { it.isNotEmpty() }?.let { comment ->
            result[comment] = path
        }
        if (node.isMap) {
            node.childrenMap().forEach { (key, child) ->
                collectComments(child, path + key.toString(), result)
            }
        }
    }

    /** 把旧节点的值合并进目标节点：仍存在的键保留旧值，多余键跳过（目标树已含缺失键的默认值）。 */
    private fun mergeOldValues(target: ConfigurationNode, old: ConfigurationNode) {
        if (!target.isMap || !old.isMap) return
        old.childrenMap().forEach { (key, oldChild) ->
            val targetChild = target.node(key.toString())
            if (targetChild.virtual()) return@forEach
            if (oldChild.isMap && targetChild.isMap) {
                mergeOldValues(targetChild, oldChild)
            } else {
                oldChild.raw()?.let { targetChild.set(it) }
            }
        }
    }
}
