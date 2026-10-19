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

package icu.h2l.login.backend.hzlimbo.config

import org.spongepowered.configurate.objectmapping.ConfigSerializable
import org.spongepowered.configurate.objectmapping.meta.Comment

/**
 * `backend-hzlimbo.conf` 的配置结构。
 *
 * 绑定地址优先复用 `core.conf` 的 `v-server.outpre.auth-host` / `auth-port`（`vServer.mode = outpre` 时）；
 * 否则使用本文件的 `bind` 配置。
 */
@ConfigSerializable
class HzLimboConfig {

    @Comment("config.hzlimbo.bind")
    @JvmField
    val bind = BindConfig()

    @Comment("config.hzlimbo.world")
    @JvmField
    val world = WorldConfig()

    // 连接读超时（秒）
    @Comment("config.hzlimbo.read-timeout-seconds")
    val readTimeoutSeconds: Int = 30

    @ConfigSerializable
    class BindConfig(
        // 监听 Host
        @param:Comment("config.hzlimbo.bind.host")
        val host: String = "127.0.0.1",

        // 监听 Port
        @param:Comment("config.hzlimbo.bind.port")
        val port: Int = 30066,
    )

    @ConfigSerializable
    class WorldConfig(
        // 出生点 X（默认 8.0 = 3x3 区块石台的正中心）
        @param:Comment("config.hzlimbo.world.spawn-x")
        val spawnX: Double = 8.0,

        // 出生点 Y
        @param:Comment("config.hzlimbo.world.spawn-y")
        val spawnY: Double = 1.0,

        // 出生点 Z（默认 8.0 = 3x3 区块石台的正中心）
        @param:Comment("config.hzlimbo.world.spawn-z")
        val spawnZ: Double = 8.0,

        // 朝向（水平角）
        @param:Comment("config.hzlimbo.world.yaw")
        val yaw: Float = 0.0f,

        // 俯仰角
        @param:Comment("config.hzlimbo.world.pitch")
        val pitch: Float = 0.0f,

        // 以出生点所在区块为中心，向四周发送的区块半径（1 = 3x3）
        @param:Comment("config.hzlimbo.world.chunk-radius")
        val chunkRadius: Int = 1,

        // 是否允许玩家自由移动（false 则原地悬停）
        @param:Comment("config.hzlimbo.world.allow-movement")
        val allowMovement: Boolean = true,

        // 是否发送世界边界（与石台范围一致）
        @param:Comment("config.hzlimbo.world.border")
        val border: Boolean = true,
    ) {
        @Comment("config.hzlimbo.world.platform")
        @JvmField
        val platform = PlatformConfig()
    }

    @ConfigSerializable
    class PlatformConfig(
        // 是否生成内置石台
        @param:Comment("config.hzlimbo.world.platform.enabled")
        val enabled: Boolean = true,

        // 石台表面所在的方块 Y（石台为 1 格厚）
        @param:Comment("config.hzlimbo.world.platform.y")
        val y: Int = 0,
    )
}
