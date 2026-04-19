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
@Suppress("ANNOTATION_WILL_BE_APPLIED_ALSO_TO_PROPERTY_OR_FIELD")
@ConfigSerializable
data class CoreConfig(
    @JvmField
    @Comment("数据库")
    val database: DatabaseSourceConfig = DatabaseSourceConfig(),
    @JvmField
    @Comment("UUID映射")
    val remap: RemapConfig = RemapConfig(),
    @JvmField
    @Comment("杂项")
    val misc: MiscConfig = MiscConfig(),
    @JvmField
    @Comment("Debug")
    val debug: DebugConfig = DebugConfig(),
    @JvmField
    @Comment("模块开关")
    val modules: ModulesConfig = ModulesConfig(),
    @JvmField
    @Comment("等待区服务器")
    val vServer: VServerConfig = VServerConfig(),
    @JvmField
    @Comment("消息")
    val messages: MessagesConfig = MessagesConfig()
)
