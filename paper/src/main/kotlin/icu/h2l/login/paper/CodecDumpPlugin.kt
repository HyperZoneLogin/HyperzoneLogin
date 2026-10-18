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

package icu.h2l.login.paper

import net.minecraft.SharedConstants
import net.minecraft.core.Holder
import net.minecraft.core.Registry
import net.minecraft.core.RegistryAccess
import net.minecraft.core.RegistrySynchronization
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.IntTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtOps
import net.minecraft.resources.RegistryOps
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.kyori.adventure.nbt.TagStringIO
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin
import java.nio.file.Files
import java.nio.file.Path

/**
 * Paper 服务端插件：把原版 RegistryAccess 的 registry codec + tags 导出成 backend-hzlimbo 需要的
 * SNBT 文本（codec_<key>.snbt / tags_<key>.snbt）。
 *
 * Paper 的注册表集合与原版不一致（会多出 `paper:` 命名空间的内容，以及 `worldgen/block_state_provider`），
 * 这里在导出时把这些 Paper 专属内容过滤掉，保证与原版客户端兼容。
 */
class CodecDumpPlugin : JavaPlugin() {

    override fun onEnable() {
        getCommand("codecgen")?.setExecutor { sender, _, _, _ ->
            runCatching { dump(sender) }
                .onFailure { sender.sendMessage("§c[codecgen] 导出失败: ${it.message}") }
            true
        }
        runCatching { dump(server.consoleSender) }
    }

    private fun dump(sender: CommandSender) {
        val frozen: RegistryAccess.Frozen = MinecraftServer.getServer().registryAccess()
        val key = codecKey(SharedConstants.getProtocolVersion())

        val outputDir: Path = dataFolder.toPath()
        Files.createDirectories(outputDir)
        val codecOut = outputDir.resolve("codec_$key.snbt")
        val tagsOut = outputDir.resolve("tags_$key.snbt")

        Files.writeString(codecOut, toSnbt(buildCodec(frozen)))
        Files.writeString(tagsOut, toSnbt(buildTags(frozen)))

        sender.sendMessage("§a[codecgen] 已导出 registry codec + tags:")
        sender.sendMessage("§7  $codecOut")
        sender.sendMessage("§7  $tagsOut")
        logger.info("[codecgen] dumped codec_$key.snbt (${Files.size(codecOut)}B) + tags_$key.snbt (${Files.size(tagsOut)}B) to $outputDir")
    }

    /** 依据协议号映射 codec 资源键，与 backend-hzlimbo 的 ProtocolVersions.codecKey 保持一致。 */
    private fun codecKey(protocol: Int): String = when {
        protocol >= 777 -> "26_3"
        protocol >= 776 -> "26_2"
        protocol >= 775 -> "26_1"
        protocol >= 774 -> "1_21_11"
        protocol >= 773 -> "1_21_9"
        protocol >= 772 -> "1_21_7"
        protocol >= 771 -> "1_21_6"
        protocol >= 770 -> "1_21_5"
        protocol >= 769 -> "1_21_4"
        protocol >= 768 -> "1_21_2"
        protocol >= 767 -> "1_21"
        protocol >= 766 -> "1_20_5"
        else -> "1_20"
    }

    /**
     * 输出与仓库内已格式化文件一致的 SNBT：2 空格缩进、LF 换行。
     *
     * Mojang 的 CompoundTag.toString() 只有紧凑单行形式，这里借 adventure-nbt 重新排版；
     * 排版失败时回退到紧凑形式，保证导出本身不会因排版而失败。
     */
    private fun toSnbt(tag: CompoundTag): String {
        val compact = tag.toString()
        return runCatching {
            SNBT_IO.asString(SNBT_IO.asCompound(compact)).replace("\r\n", "\n") + "\n"
        }.getOrElse {
            logger.warning("[codecgen] SNBT 排版失败，回退到紧凑格式: ${it.message}")
            compact + "\n"
        }
    }

    private companion object {
        private val SNBT_IO: TagStringIO = TagStringIO.builder().indent(2).build()
    }

    private fun buildCodec(frozen: RegistryAccess.Frozen): CompoundTag {
        val codec = CompoundTag()
        RegistrySynchronization.packRegistries(
            RegistryOps.create(NbtOps.INSTANCE, frozen),
            frozen,
            setOf(),
        ) { key: ResourceKey<out Registry<*>>, entries: List<RegistrySynchronization.PackedRegistryEntry> ->
            val registryName = key.identifier().toString()
            if (isAllowedRegistry(registryName)) {
                val registryTag = CompoundTag()
                registryTag.putString("type", registryName)
                val value = ListTag()
                var id = 0
                for (entry in entries) {
                    val entryName = entry.id().toString()
                    if (entryName.startsWith("paper:")) continue
                    val e = CompoundTag()
                    e.putString("name", entryName)
                    e.putInt("id", id++)
                    entry.data().ifPresent { e.put("element", it) }
                    value.add(e)
                }
                registryTag.put("value", value)
                codec.put(registryName, registryTag)
            }
        }
        return codec
    }

    private fun buildTags(frozen: RegistryAccess.Frozen): CompoundTag {
        val tags = CompoundTag()
        for (entry in frozen.registries()) {
            val registryName = entry.key().identifier().toString()
            if (!isAllowedTagRegistry(registryName)) continue
            collectTags(entry.value(), registryName, tags)
        }
        return tags
    }

    /** 客户端认识的注册表：排除 Paper 专属（paper: 命名空间）与 block_state_provider。 */
    private fun isAllowedRegistry(name: String): Boolean =
        !name.startsWith("paper:") && name != "minecraft:worldgen/block_state_provider"

    /** tags 额外排除 worldgen 系列（除 biome），与 backend-hzlimbo 的过滤一致。 */
    private fun isAllowedTagRegistry(name: String): Boolean {
        if (name.startsWith("paper:")) return false
        return !name.startsWith("minecraft:worldgen/") || name == "minecraft:worldgen/biome"
    }

    private fun <T : Any> collectTags(registry: Registry<T>, name: String, out: CompoundTag) {
        val registryTags = CompoundTag()
        for (named in registry.getTags()) {
            val tagName = named.key().location().toString()
            if (tagName.startsWith("paper:")) continue
            val ids = ListTag()
            for (holder: Holder<T> in named) {
                ids.add(IntTag.valueOf(registry.getId(holder.value())))
            }
            registryTags.put(tagName, ids)
        }
        if (!registryTags.isEmpty) {
            out.put(name, registryTags)
        }
    }
}
