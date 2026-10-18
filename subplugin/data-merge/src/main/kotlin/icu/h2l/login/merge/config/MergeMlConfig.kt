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

package icu.h2l.login.merge.config

import org.spongepowered.configurate.objectmapping.ConfigSerializable
import org.spongepowered.configurate.objectmapping.meta.Comment

@ConfigSerializable
class MergeMlConfig {
    // 数据库配置
    @Comment("config.merge-ml.source")
    var source: SourceConfig = SourceConfig()

    // 表名配置
    @Comment("config.merge-ml.tables")
    var tables: SourceTables = SourceTables()

    // MultiLogin服务ID 到 HZL入口ID 映射，未配置时默认使用 ml_{服务ID}
    @Comment("config.merge-ml.service-id-mapping")
    var serviceIdMapping: MutableMap<Int, String> = mutableMapOf(
        1 to "mojang"
    )

    // 指定哪些 MultiLogin 服务ID 属于 Floodgate 服务。
    // 匹配的记录将使用 Floodgate 特殊迁移逻辑：
    // 将 online_uuid 转换为 XUID 并写入 floodgate_auth 表，而非普通入口表。
    @Comment("config.merge-ml.floodgate-service-ids")
    var floodgateServiceIds: MutableList<Int> = mutableListOf()

    @ConfigSerializable
    class SourceConfig {
        // 库类型，支持 H2DB 或 MYSQL
        @Comment("config.merge-ml.source.type")
        var type: String = "H2DB"

        // H2 配置
        @Comment("config.merge-ml.source.h2")
        var h2: H2Config = H2Config()

        // MySQL 配置
        @Comment("config.merge-ml.source.mysql")
        var mysql: MysqlConfig = MysqlConfig()
    }

    @ConfigSerializable
    class H2Config {
        // 可选：直接指定 JDBC URL。留空时按 path + parameters 生成
        @Comment("config.merge-ml.h2.jdbc-url")
        var jdbcUrl: String = ""

        // 文件路径（相对于插件数据目录）
        @Comment("config.merge-ml.h2.path")
        var path: String = "data-merge/multilogin"

        // JDBC 附加参数
        @Comment("config.merge-ml.h2.parameters")
        var parameters: String = "MODE=MySQL"

        // 用户名
        @Comment("config.merge-ml.h2.username")
        var username: String = "root"

        // 密码
        @Comment("config.merge-ml.h2.password")
        var password: String = "root"
    }

    @ConfigSerializable
    class MysqlConfig {
        // 地址
        @Comment("config.merge-ml.mysql.host")
        var host: String = "127.0.0.1"

        // 端口
        @Comment("config.merge-ml.mysql.port")
        var port: Int = 3306

        // 库名
        @Comment("config.merge-ml.mysql.database")
        var database: String = "mixed_login"

        // 用户名
        @Comment("config.merge-ml.mysql.username")
        var username: String = "root"

        // 密码
        @Comment("config.merge-ml.mysql.password")
        var password: String = "password"

        // JDBC 附加参数
        @Comment("config.merge-ml.mysql.parameters")
        var parameters: String = "useSSL=false&serverTimezone=UTC&characterEncoding=utf8"
    }

    @ConfigSerializable
    class SourceTables {
        // UserDataTableV3 的表名
        @Comment("config.merge-ml.tables.user-data-table")
        var userDataTable: String = "multilogin_user_data_v3"

        // InGameProfileTableV3 的表名
        @Comment("config.merge-ml.tables.in-game-profile-table")
        var inGameProfileTable: String = "multilogin_in_game_profile_v3"
    }
}
