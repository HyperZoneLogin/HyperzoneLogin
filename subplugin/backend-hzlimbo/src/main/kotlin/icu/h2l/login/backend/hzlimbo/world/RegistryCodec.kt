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
 * 加载并缓存每个协议版本对应的 registry codec（`codec_*.snbt`）与 registry tags（`tags_*.snbt`）。
 *
 * 这些 `.snbt` 文件是「维度的注册表快照数据」（dimension_type、worldgen/biome 等 + tags），
 * 由真实的 Minecraft 服务端数据生成器产出；limbo 没有后端服务，因此必须自带这些版本相关数据，
 * 在 configuration 阶段下发给客户端。
 *
 * 资源文件以 SNBT（stringified NBT）文本存放，方便 git 比对；读取时解析为 [CompoundBinaryTag]。
 */
object RegistryCodec {

    private const val RESOURCE_PREFIX = "/dimension/"

    private val codecs = ConcurrentHashMap<String, CompoundBinaryTag>()
    private val tags = ConcurrentHashMap<String, Map<String, Map<String, IntArray>>>()

    /** 依据协议号返回 registry codec。 */
    fun codec(protocol: Int): CompoundBinaryTag =
        codecs.computeIfAbsent(ProtocolVersions.codecKey(protocol)) { key ->
            readCompound("${RESOURCE_PREFIX}codec_$key.snbt")
        }

    /** 依据协议号返回 registry tags（仅 1.20.5+ 使用）。 */
    fun tags(protocol: Int): Map<String, Map<String, IntArray>> =
        tags.computeIfAbsent(ProtocolVersions.tagsKey(protocol)) { key ->
            parseTags(readCompound("${RESOURCE_PREFIX}tags_$key.snbt"))
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
     *
     * 结构与 NanoLimbo `DimensionRegistry.parseUpdateTags` 一致。
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

    /**
     * 返回 codec 顶层每个 registry 的条目，形如 `(registryType, [(name, element)])`。
     *
     * 仅用于 1.20.5+ 的 per-registry registry data 下发。
     */
    fun registries(codec: CompoundBinaryTag): List<Pair<String, List<Pair<String, BinaryTag?>>>> =
        codec.map { (registryType, raw) ->
            val compound = raw as CompoundBinaryTag
            val values = compound.getList("value")
            val entries = values.map { entry ->
                val tag = entry as CompoundBinaryTag
                tag.getString("name") to tag.get("element")
            }
            registryType to entries
        }
}
