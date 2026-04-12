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

package icu.h2l.login.config

import org.spongepowered.configurate.objectmapping.ConfigSerializable
import org.spongepowered.configurate.objectmapping.meta.Comment

@ConfigSerializable
class MessagesConfig {
    @Comment("默认语言；当未启用客户端语言检测，或客户端语言没有对应语言文件时使用")
    val defaultLocale: String = "zh_cn"

    @Comment("消息缺失时的回退语言；建议始终保留 en_us 或 zh_cn 之一")
    val fallbackLocale: String = "en_us"

    @Comment("是否优先尝试读取客户端语言（如 en_us / zh_cn / ru_ru）；若失败则回退 defaultLocale")
    val useClientLocale: Boolean = true
}


