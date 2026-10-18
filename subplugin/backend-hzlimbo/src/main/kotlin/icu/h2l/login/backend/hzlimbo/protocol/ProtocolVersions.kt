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

package icu.h2l.login.backend.hzlimbo.protocol

/**
 * 1.20.2+ 相关协议版本号常量，以及 codec/tags 资源文件的分组键。
 *
 * 这里刻意按「协议号」而不是 `ProtocolVersion` 枚举名做比较：Mojang 的版本号（如 1.21.8）与
 * NanoLimbo 的命名（1.21.7）存在错位，直接比较协议号最不容易出错。
 *
 * 数据来源：NanoLimbo `Version` 枚举与 `DimensionRegistry.getRegistryByVersion()`。
 */
object ProtocolVersions {

    /** 本模块支持的最低协议号（1.20.2，configuration 阶段起始）。 */
    const val MIN_PROTOCOL = 764

    const val P_1_20_2 = 764
    const val P_1_20_3 = 765
    const val P_1_20_5 = 766
    const val P_1_21 = 767
    const val P_1_21_2 = 768
    const val P_1_21_4 = 769
    const val P_1_21_5 = 770
    const val P_1_21_6 = 771
    const val P_1_21_7 = 772
    const val P_1_21_9 = 773
    const val P_1_21_11 = 774
    const val P_26_1 = 775
    const val P_26_2 = 776
    const val P_26_3 = 777

    fun isSupported(protocol: Int): Boolean = protocol >= MIN_PROTOCOL

    /**
     * 依据协议号选择 registry codec 资源键（不含 `.nbt` 后缀与目录前缀）。
     */
    fun codecKey(protocol: Int): String = when {
        protocol >= P_26_3 -> "26_3"
        protocol >= P_26_2 -> "26_2"
        protocol >= P_26_1 -> "26_1"
        protocol >= P_1_21_11 -> "1_21_11"
        protocol >= P_1_21_9 -> "1_21_9"
        protocol >= P_1_21_7 -> "1_21_7"
        protocol >= P_1_21_6 -> "1_21_6"
        protocol >= P_1_21_5 -> "1_21_5"
        protocol >= P_1_21_4 -> "1_21_4"
        protocol >= P_1_21_2 -> "1_21_2"
        protocol >= P_1_21 -> "1_21"
        protocol >= P_1_20_5 -> "1_20_5"
        // 1.20.2 ~ 1.20.4 复用 1.20 的 registry codec。
        else -> "1_20"
    }

    /**
     * 依据协议号选择 registry tags 资源键（仅 1.20.5+ 使用）。
     */
    fun tagsKey(protocol: Int): String = when {
        protocol >= P_26_3 -> "26_3"
        protocol >= P_26_2 -> "26_2"
        protocol >= P_26_1 -> "26_1"
        protocol >= P_1_21_11 -> "1_21_11"
        protocol >= P_1_21_9 -> "1_21_9"
        protocol >= P_1_21_7 -> "1_21_7"
        protocol >= P_1_21_6 -> "1_21_6"
        protocol >= P_1_21_5 -> "1_21_5"
        protocol >= P_1_21_4 -> "1_21_4"
        protocol >= P_1_21_2 -> "1_21_2"
        protocol >= P_1_21 -> "1_21"
        else -> "1_20_5"
    }
}
