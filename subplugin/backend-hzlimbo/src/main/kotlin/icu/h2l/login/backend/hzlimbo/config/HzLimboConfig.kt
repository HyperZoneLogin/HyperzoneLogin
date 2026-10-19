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
}
