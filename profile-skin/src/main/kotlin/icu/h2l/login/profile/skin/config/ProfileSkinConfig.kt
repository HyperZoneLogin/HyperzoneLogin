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

package icu.h2l.login.profile.skin.config

import org.spongepowered.configurate.objectmapping.ConfigSerializable
import org.spongepowered.configurate.objectmapping.meta.Comment

@ConfigSerializable
class ProfileSkinConfig {
    @Comment("是否启用 profile-skin 模块")
    var enabled: Boolean = true

    @Comment("是否优先缓存并使用上游已经携带签名的 textures")
    var preferUpstreamSignedTextures: Boolean = true

    @Comment("仅这些 entryId 的 signed textures 会被直接信任，其他 entry 即使 signed 也会优先尝试修复")
    var trustedSignedTextureEntries: Set<String> = setOf("mojang")

    @Comment("当上游 textures 没有签名时，是否尝试通过 MineSkin 修复")
    var restoreUnsignedTextures: Boolean = true


    @Comment("MineSkin 修复配置")
    var mineSkin: MineSkinConfig = MineSkinConfig()
}

@ConfigSerializable
class MineSkinConfig {
    @Comment("生成方式：URL 或 UPLOAD")
    var method: String = "URL"

    @Comment("URL 模式接口地址")
    var urlEndpoint: String = "https://api.mineskin.org/generate/url"

    @Comment("上传模式接口地址")
    var uploadEndpoint: String = "https://api.mineskin.org/generate/upload"

    @Comment("请求超时时间（毫秒）")
    var timeoutMillis: Long = 15000

    @Comment("HTTP User-Agent")
    var userAgent: String = "HyperZoneLogin/1.0"
}

enum class MineSkinMethod {
    URL,
    UPLOAD;

    companion object {
        fun from(raw: String?): MineSkinMethod {
            return entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: URL
        }
    }
}

