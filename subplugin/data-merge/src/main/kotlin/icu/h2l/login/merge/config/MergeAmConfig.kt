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
class MergeAmConfig {
    // 数据库配置
    @Comment("config.merge-am.source")
    var source: SourceConfig = SourceConfig()

    // 表配置
    @Comment("config.merge-am.tables")
    var tables: SourceTables = SourceTables()

    @ConfigSerializable
    class SourceConfig {
        // 库类型，支持 SQLITE 或 MYSQL
        @Comment("config.merge-am.source.type")
        var type: String = "SQLITE"

        // SQLite 配置
        @Comment("config.merge-am.source.sqlite")
        var sqlite: SqliteConfig = SqliteConfig()

        // MySQL 配置
        @Comment("config.merge-am.source.mysql")
        var mysql: MysqlConfig = MysqlConfig()
    }

    @ConfigSerializable
    class SqliteConfig {
        // 可选：直接指定 JDBC URL。留空时按 path + parameters 生成
        @Comment("config.merge-am.sqlite.jdbc-url")
        var jdbcUrl: String = ""

        // SQLite 文件路径（相对于插件数据目录）
        @Comment("config.merge-am.sqlite.path")
        var path: String = "data-merge/authme.db"

        // 可选：JDBC 附加参数
        @Comment("config.merge-am.sqlite.parameters")
        var parameters: String = ""
    }

    @ConfigSerializable
    class MysqlConfig {
        // 地址
        @Comment("config.merge-am.mysql.host")
        var host: String = "127.0.0.1"

        // 端口
        @Comment("config.merge-am.mysql.port")
        var port: Int = 3306

        // 库名
        @Comment("config.merge-am.mysql.database")
        var database: String = "authme"

        // 用户名
        @Comment("config.merge-am.mysql.username")
        var username: String = "root"

        // 密码
        @Comment("config.merge-am.mysql.password")
        var password: String = "password"

        // 可选：JDBC 附加参数
        @Comment("config.merge-am.mysql.parameters")
        var parameters: String = "useSSL=false&serverTimezone=UTC&characterEncoding=utf8"
    }

    @ConfigSerializable
    class SourceTables {
        // 表名
        @Comment("config.merge-am.tables.authme-table")
        var authMeTable: String = "authme"
    }
}
