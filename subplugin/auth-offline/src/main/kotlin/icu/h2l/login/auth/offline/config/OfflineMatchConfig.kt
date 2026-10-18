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

package icu.h2l.login.auth.offline.config

import org.spongepowered.configurate.objectmapping.ConfigSerializable
import org.spongepowered.configurate.objectmapping.meta.Comment

@ConfigSerializable
class OfflineMatchConfig {

    // 是否允许进行匹配
    @Comment("config.offline.match.enable")
    val enable = true

    // UUID匹配设定
    @Comment("config.offline.match.uuid")
    val uuidMatch = UUIDMatch()

    // Host匹配设定
    @Comment("config.offline.match.host")
    val hostMatch = HostMatch()

    @ConfigSerializable
    class UUIDMatch {
        @ConfigSerializable
        class PCL2 {
            // PCL2的UUID匹配
            @Comment("config.offline.match.pcl2.enable")
            val enable = true

            // PCL2的UUID进行哈希计算匹配
            @Comment("config.offline.match.pcl2.hash")
            val hash = true

            // PCL2的苗条模型UUID匹配
            @Comment("config.offline.match.pcl2.slim")
            val slim = true
        }

        // 是否允许全0的UUID(Zalith) 匹配为离线
        @Comment("config.offline.match.uuid.zero")
        val zero = true

        // 是否允许默认uuid生成方法 匹配为离线
        @Comment("config.offline.match.uuid.offline")
        val offline = true

        // 关于PCL2启动器匹配的细节设定
        @Comment("config.offline.match.uuid.pcl2")
        val pcl2 = PCL2()
    }

    @ConfigSerializable
    class HostMatch {
        val start = listOf("offline", "o-")
    }
}
