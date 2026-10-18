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

package icu.h2l.login.profile.skin.data

import com.velocitypowered.api.util.GameProfile

/**
 * 支持的皮肤模型常量与归一化工具。
 */
object ProfileSkinModel {
    /** 经典 Steve 模型。 */
    const val CLASSIC = "classic"

    /** 细臂 Alex 模型。 */
    const val SLIM = "slim"

    /**
     * 将任意模型字符串归一化为 [CLASSIC] 或 [SLIM]。
     */
    fun normalize(model: String?): String {
        return if (model.equals(SLIM, ignoreCase = true)) SLIM else CLASSIC
    }
}

/**
 * 皮肤资源来源定义。
 *
 * @property skinUrl 皮肤图片地址
 * @property model 皮肤模型类型
 */
data class ProfileSkinSource(
    val skinUrl: String,
    val model: String = ProfileSkinModel.CLASSIC
) {
    /**
     * 返回模型字段已归一化的新对象。
     */
    fun normalized(): ProfileSkinSource {
        return copy(model = ProfileSkinModel.normalize(model))
    }
}

/**
 * 可注入到 [GameProfile] 的皮肤纹理数据。
 *
 * @property value textures 属性值
 * @property signature 可选签名；若为空则不能直接构造 Velocity property
 */
data class ProfileSkinTextures(
    val value: String,
    val signature: String? = null
) {
    /**
     * Velocity 当前只提供“必须携带完整 signature”的 `GameProfile.Property` 构造器。
     *
     * 因此：
     * 1. 有非空白签名时，才能安全构造 property；
     * 2. 无签名或空白签名时，必须由上层决定“跳过注入”或“回退到其它完整资料”，
     *    绝不能把空签名直接传给 Velocity。
     */
    fun toPropertyOrNull(): GameProfile.Property? {
        if (signature.isNullOrBlank()) {
            return null
        }
        return GameProfile.Property("textures", value, signature)
    }

    /**
     * 将当前纹理数据强制转换为 [GameProfile.Property]。
     *
     * 若签名为空则直接抛错。
     */
    fun toProperty(): GameProfile.Property {
        return requireNotNull(toPropertyOrNull()) {
            "ProfileSkinTextures cannot be converted to GameProfile.Property without a non-blank signature"
        }
    }

    /**
     * 当前纹理是否携带非空白签名。
     */
    val isSigned: Boolean
        get() = !signature.isNullOrBlank()
}

