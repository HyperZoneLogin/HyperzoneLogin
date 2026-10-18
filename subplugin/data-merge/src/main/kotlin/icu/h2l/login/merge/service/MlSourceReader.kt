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

package icu.h2l.login.merge.service

import icu.h2l.login.merge.config.MergeMlConfig
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.*

data class MlInGameProfileRow(
    val inGameUuid: UUID,
    val currentUsernameLowerCase: String?,
    val currentUsernameOriginal: String?
)

data class MlUserDataRow(
    val onlineUuid: UUID,
    val onlineName: String?,
    val serviceId: Int,
    val inGameProfileUuid: UUID?,
    val whitelist: Boolean
)

class MlSourceReader(
    private val dataDirectory: Path,
    private val config: MergeMlConfig
) {
    fun readProfilesAndUserData(): Pair<List<MlInGameProfileRow>, List<MlUserDataRow>> {
        connect().use { connection ->
            return readProfiles(connection) to readUserData(connection)
        }
    }

    private fun connect(): Connection {
        val source = config.source
        return when (source.type.uppercase()) {
            "MYSQL" -> {
                Class.forName("com.mysql.cj.jdbc.Driver")
                val mysql = source.mysql
                val url = "jdbc:mysql://${mysql.host}:${mysql.port}/${mysql.database}?${mysql.parameters}"
                DriverManager.getConnection(url, mysql.username, mysql.password)
            }
            "H2DB", "H2" -> {
                val h2 = source.h2
                val url = if (h2.jdbcUrl.isNotBlank()) {
                    h2.jdbcUrl
                } else {
                    val absolutePath = resolveH2DatabaseBasePath(h2.path)
                    ensureH2DatabaseFileExists(absolutePath)
                    val dbPath = absolutePath.toString().replace('\\', '/')
                    "jdbc:h2:file:$dbPath;${h2.parameters}"
                }

                Class.forName("org.h2.Driver")
                try {
                    DriverManager.getConnection(url, h2.username, h2.password)
                } catch (ex: SQLException) {
                    if (ex.message?.contains("90048") == true) {
                        throw IllegalStateException(
                            "检测到 H2 数据库版本不兼容（90048）。当前驱动无法直接读取该 .mv.db，请先用旧版 H2 导出脚本再导入到 2.x，或在 merge-ml.conf 的 source.h2.jdbcUrl 填入可访问的兼容数据源 URL。原始错误: ${ex.message}",
                            ex
                        )
                    }
                    throw ex
                }
            }
            else -> {
                throw IllegalArgumentException("不支持的源数据库类型: ${source.type}")
            }
        }
    }

    private fun resolveH2DatabaseBasePath(configuredPathText: String): Path {
        val normalizedPath = configuredPathText.removeSuffix(".mv.db").removeSuffix(".h2.db")
        val configuredPath = Paths.get(normalizedPath)
        return if (configuredPath.isAbsolute) {
            configuredPath.normalize()
        } else {
            dataDirectory.toAbsolutePath().normalize().resolve(configuredPath).normalize()
        }
    }

    private fun ensureH2DatabaseFileExists(absolutePath: Path) {
        val candidateFiles = listOf(
            absolutePath.resolveSibling("${absolutePath.fileName}.mv.db"),
            absolutePath.resolveSibling("${absolutePath.fileName}.h2.db")
        )
        if (candidateFiles.any(Files::isRegularFile)) {
            return
        }

        val expectedFiles = candidateFiles.joinToString(" / ") { "'$it'" }
        throw IllegalStateException(
            "未找到 ML H2 数据库文件，请先将 MultiLogin 数据库文件放到 $expectedFiles，" +
                "或修改 merge-ml.conf 中的 source.h2.path / source.h2.jdbcUrl，避免 H2 自动创建空库。"
        )
    }

    private fun readProfiles(connection: Connection): List<MlInGameProfileRow> {
        val sql = "SELECT in_game_uuid, current_username_lower_case, current_username_original FROM ${config.tables.inGameProfileTable}"
        connection.prepareStatement(sql).use { statement ->
            statement.executeQuery().use { rs ->
                val result = mutableListOf<MlInGameProfileRow>()
                while (rs.next()) {
                    val inGameUuid = bytesToUuid(rs.getBytes("in_game_uuid")) ?: continue
                    result.add(
                        MlInGameProfileRow(
                            inGameUuid = inGameUuid,
                            currentUsernameLowerCase = rs.getString("current_username_lower_case"),
                            currentUsernameOriginal = rs.getString("current_username_original")
                        )
                    )
                }
                return result
            }
        }
    }

    private fun readUserData(connection: Connection): List<MlUserDataRow> {
        val sql = "SELECT online_uuid, online_name, service_id, in_game_profile_uuid, whitelist FROM ${config.tables.userDataTable}"
        connection.prepareStatement(sql).use { statement ->
            statement.executeQuery().use { rs ->
                val result = mutableListOf<MlUserDataRow>()
                while (rs.next()) {
                    val onlineUuid = bytesToUuid(rs.getBytes("online_uuid")) ?: continue
                    result.add(
                        MlUserDataRow(
                            onlineUuid = onlineUuid,
                            onlineName = rs.getString("online_name"),
                            serviceId = rs.getInt("service_id"),
                            inGameProfileUuid = bytesToUuid(rs.getBytes("in_game_profile_uuid")),
                            whitelist = rs.getBoolean("whitelist")
                        )
                    )
                }
                return result
            }
        }
    }

    private fun bytesToUuid(bytes: ByteArray?): UUID? {
        if (bytes == null || bytes.size != 16) {
            return null
        }
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long)
    }
}
