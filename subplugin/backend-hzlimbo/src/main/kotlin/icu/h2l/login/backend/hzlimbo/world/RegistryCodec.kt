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

package icu.h2l.login.backend.hzlimbo.world

import icu.h2l.login.backend.hzlimbo.protocol.ProtocolVersions
import net.kyori.adventure.nbt.BinaryTag
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.nbt.IntBinaryTag
import net.kyori.adventure.nbt.ListBinaryTag
import net.kyori.adventure.nbt.TagStringIO
import java.util.concurrent.ConcurrentHashMap

/**
 * 加载并缓存每个协议版本对应的精简 registry codec / tags。
 *
 * 裁剪规则见 [MinimalRegistryTable]。资源文件本身可以已是裁剪后的版本（发布体积更小），
 * 这里的裁剪是幂等的：对已裁剪数据再跑一次规则不会继续丢条目。
 *
 * 资源文件使用 SNBT（stringified NBT）文本，方便 git 比对；读取时解析为 [CompoundBinaryTag]。
 */
object RegistryCodec {

    private const val RESOURCE_PREFIX = "/dimension/"

    private val fullCodecs = ConcurrentHashMap<String, CompoundBinaryTag>()
    private val rawTags = ConcurrentHashMap<String, Map<String, Map<String, IntArray>>>()
    private val filtered = ConcurrentHashMap<String, Filtered>()

    /**
     * 精简后的 registry 数据。
     *
     * - [codec]：1.20.2 ~ 1.20.4 的整份 codec（单包下发）；
     * - [registries]：1.20.5+ 的逐 registry 条目；
     * - [tags]：需要下发的 tags（id 已按裁剪后的条目重映射）。
     */
    data class Filtered(
        val codec: CompoundBinaryTag,
        val registries: List<Pair<String, List<Pair<String, BinaryTag?>>>>,
        val tags: Map<String, Map<String, IntArray>>,
    )

    /** 依据协议号返回裁剪后的 codec / registries / tags。 */
    fun filtered(protocol: Int): Filtered =
        filtered.computeIfAbsent(ProtocolVersions.codecKey(protocol)) {
            buildFiltered(protocol)
        }

    private fun buildFiltered(protocol: Int): Filtered {
        val full = fullCodecs.computeIfAbsent(ProtocolVersions.codecKey(protocol)) { key ->
            readCompound("${RESOURCE_PREFIX}codec_$key.snbt")
        }
        // 1.20.2 ~ 1.20.4 不发 tags，Tagged 规则退化为 All。
        val allTags = if (protocol >= ProtocolVersions.P_1_20_5) {
            rawTags.computeIfAbsent(ProtocolVersions.tagsKey(protocol)) { key ->
                parseTags(readCompound("${RESOURCE_PREFIX}tags_$key.snbt"))
            }
        } else {
            emptyMap()
        }

        val registries = ArrayList<Pair<String, List<Pair<String, BinaryTag?>>>>()
        val tags = LinkedHashMap<String, Map<String, IntArray>>()
        val codecBuilder = CompoundBinaryTag.builder()

        for ((registryType, rule) in MinimalRegistryTable.CODEC) {
            if (registryType !in full.keySet()) continue

            val entries = full.getCompound(registryType).getList("value").map { raw ->
                val entry = raw as CompoundBinaryTag
                RawEntry(
                    originalId = entry.getInt("id"),
                    name = entry.getString("name"),
                    element = entry.get("element"),
                )
            }
            val registryTags = allTags[registryType]
            val kept = applyRule(rule, entries, registryTags)
            if (kept.isEmpty()) continue

            // 客户端按列表顺序重新分配 id；记录旧 id -> 新 id 供 tags 重映射。
            val idMap = kept.withIndex().associate { (index, entry) -> entry.originalId to index }

            registries += registryType to kept.map { it.name to it.element }

            val valueList = ListBinaryTag.builder()
            kept.forEachIndexed { index, entry ->
                val entryTag = CompoundBinaryTag.builder()
                entryTag.putString("name", entry.name)
                entryTag.putInt("id", index)
                if (entry.element != null) {
                    entryTag.put("element", entry.element)
                }
                valueList.add(entryTag.build())
            }
            val registryTag = CompoundBinaryTag.builder()
            registryTag.putString("type", registryType)
            registryTag.put("value", valueList.build())
            codecBuilder.put(registryType, registryTag.build())

            val tagNames = MinimalRegistryTable.TAGS[registryType]
            if (tagNames != null && registryTags != null) {
                val out = LinkedHashMap<String, IntArray>()
                for (tagName in tagNames) {
                    val ids = registryTags[tagName] ?: continue
                    out[tagName] = ids.toList().mapNotNull { idMap[it] }.toIntArray()
                }
                if (out.isNotEmpty()) {
                    tags[registryType] = out
                }
            }
        }

        // 静态 registry 的 tags（如 block 的 infiniburn_overworld）：不需要裁剪 codec，
        // 也不需要重映射 id，直接按原样下发。
        for ((registryType, tagNames) in MinimalRegistryTable.TAGS) {
            if (registryType in MinimalRegistryTable.CODEC) continue
            val registryTags = allTags[registryType] ?: continue
            val out = LinkedHashMap<String, IntArray>()
            for (tagName in tagNames) {
                registryTags[tagName]?.let { out[tagName] = it }
            }
            if (out.isNotEmpty()) {
                tags[registryType] = out
            }
        }

        return Filtered(codecBuilder.build(), registries, tags)
    }

    private data class RawEntry(val originalId: Int, val name: String, val element: BinaryTag?)

    private fun applyRule(
        rule: MinimalRegistryTable.Rule,
        entries: List<RawEntry>,
        tags: Map<String, IntArray>?,
    ): List<RawEntry> = when (rule) {
        is MinimalRegistryTable.Rule.All -> entries
        is MinimalRegistryTable.Rule.Named -> entries.filter { it.name in rule.names }
        is MinimalRegistryTable.Rule.First -> entries.take(rule.count)
        is MinimalRegistryTable.Rule.Tagged ->
            if (tags == null) {
                // 老版本不发送 tags，保守保留全部。
                entries
            } else {
                val ids = rule.tags.flatMap { tag -> tags[tag]?.toList() ?: emptyList() }.toSet()
                entries.filter { it.originalId in ids || it.name in rule.names }
            }
    }

    private fun readCompound(path: String): CompoundBinaryTag {
        val input = RegistryCodec::class.java.getResourceAsStream(path)
            ?: error("Missing bundled registry resource: $path")
        return input.bufferedReader(Charsets.UTF_8).use { reader ->
            TagStringIO.tagStringIO().asCompound(reader.readText())
        }
    }

    /**
     * 把 tags NBT 解析为 `registry -> (tag -> entry ids)`。
     */
    private fun parseTags(root: CompoundBinaryTag): Map<String, Map<String, IntArray>> {
        val result = HashMap<String, Map<String, IntArray>>()
        for ((registry, rawSub) in root) {
            val sub = rawSub as CompoundBinaryTag
            val subMap = HashMap<String, IntArray>()
            for ((tagName, rawIds) in sub) {
                val ids = (rawIds as ListBinaryTag).map { (it as IntBinaryTag).value() }.toIntArray()
                subMap[tagName] = ids
            }
            result[registry] = subMap
        }
        return result
    }
}
