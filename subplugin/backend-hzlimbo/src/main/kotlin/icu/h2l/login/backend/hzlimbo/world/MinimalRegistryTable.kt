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

/**
 * limbo 精简注册表规则表。
 *
 * 客户端在 configuration / play 阶段会对以下内容做**硬引用**，所以这些 registry 和标签必须存在：
 *
 * - JoinGame：`dimension_type` 的 id 0（overworld）；
 * - `PalettedContainerFactory`：`worldgen/biome` 的 `minecraft:plains`；
 * - 26.x 时钟：`dimension_type.default_clock` / `timelines` 引用 `world_clock` 与 `timeline` 标签；
 * - 物品组件初始化（`RegistryDataCollector.updateComponents`）：`damage_type` / `banner_pattern` 标签、
 *   `jukebox_song`、`block_transformer`、`decorated_pot_pattern`、`trim_material`、`instrument`、
 *   `chicken_variant`；
 * - `RegistryDataLoader` 的“必须非空”校验：各实体变体 registry。
 *
 * 这里的规则同时用于运行时裁剪与资源文件预裁剪（发布时缩小体积）。
 */
object MinimalRegistryTable {

    /** 单个 registry 的条目保留规则。 */
    sealed interface Rule {
        /** 保留全部条目。 */
        data object All : Rule

        /** 只保留指定名字的条目。 */
        data class Named(val names: Set<String>) : Rule

        /** 只保留前 [count] 条（客户端只要求非空时用）。 */
        data class First(val count: Int) : Rule

        /**
         * 只保留被 [tags] 引用的条目，外加 [names] 指定的条目。
         *
         * 用于 `damage_type` / `banner_pattern` 这类“客户端通过标签或具体 key 查找”的 registry；
         * 条目 id 会在裁剪后重新编号，标签 id 由 [RegistryCodec] 同步重映射。
         */
        data class Tagged(val tags: Set<String>, val names: Set<String> = emptySet()) : Rule
    }

    /** 需要下发的 tags：registry -> tag 名集合。 */
    val TAGS: Map<String, Set<String>> = mapOf(
        "minecraft:timeline" to setOf(
            "minecraft:in_overworld",
        ),
        "minecraft:damage_type" to setOf(
            "minecraft:is_fire",
            "minecraft:bypasses_shield",
            "minecraft:is_explosion",
        ),
        "minecraft:banner_pattern" to setOf(
            "minecraft:pattern_item/bordure_indented",
            "minecraft:pattern_item/creeper",
            "minecraft:pattern_item/field_masoned",
            "minecraft:pattern_item/flow",
            "minecraft:pattern_item/flower",
            "minecraft:pattern_item/globe",
            "minecraft:pattern_item/guster",
            "minecraft:pattern_item/mojang",
            "minecraft:pattern_item/piglin",
            "minecraft:pattern_item/skull",
        ),
    )

    /** codec 条目规则：registry -> [Rule]。 */
    val CODEC: Map<String, Rule> = mapOf(
        // JoinGame / 维度 / 时钟
        "minecraft:dimension_type" to Rule.Named(setOf("minecraft:overworld")),
        "minecraft:world_clock" to Rule.Named(setOf("minecraft:overworld")),
        "minecraft:worldgen/biome" to Rule.Named(setOf("minecraft:plains")),
        "minecraft:timeline" to Rule.All,
        // 物品组件按 key 或标签引用
        "minecraft:damage_type" to Rule.Tagged(
            tags = TAGS.getValue("minecraft:damage_type"),
            // spear 物品的 DAMAGE_TYPE 组件按具体 key 引用
            names = setOf("minecraft:spear"),
        ),
        "minecraft:banner_pattern" to Rule.Tagged(TAGS.getValue("minecraft:banner_pattern")),
        "minecraft:jukebox_song" to Rule.All,
        "minecraft:block_transformer" to Rule.All,
        "minecraft:decorated_pot_pattern" to Rule.All,
        "minecraft:trim_material" to Rule.All,
        "minecraft:instrument" to Rule.Named(setOf("minecraft:ponder_goat_horn")),
        "minecraft:chicken_variant" to Rule.All,
        // 客户端 RegistryDataLoader 强制要求非空
        "minecraft:cat_sound_variant" to Rule.First(1),
        "minecraft:cat_variant" to Rule.First(1),
        "minecraft:chicken_sound_variant" to Rule.First(1),
        "minecraft:cow_sound_variant" to Rule.First(1),
        "minecraft:cow_variant" to Rule.First(1),
        "minecraft:frog_variant" to Rule.First(1),
        "minecraft:painting_variant" to Rule.First(1),
        "minecraft:pig_sound_variant" to Rule.First(1),
        "minecraft:pig_variant" to Rule.First(1),
        "minecraft:wolf_sound_variant" to Rule.First(1),
        "minecraft:wolf_variant" to Rule.First(1),
        "minecraft:zombie_nautilus_variant" to Rule.First(1),
    )
}
