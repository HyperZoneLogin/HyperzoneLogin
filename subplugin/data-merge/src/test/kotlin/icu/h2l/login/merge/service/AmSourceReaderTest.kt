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

import icu.h2l.login.merge.config.MergeAmConfig
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.util.Comparator

class AmSourceReaderTest {
    @Test
    fun `missing sqlite file fails before opening empty database`() {
        val dataDirectory = Files.createTempDirectory("am-source-reader-test")
        try {
            val config = MergeAmConfig().apply {
                source.type = "SQLITE"
                source.sqlite.path = "data-merge/authme.db"
            }

            val exception = assertThrows<IllegalStateException> {
                AmSourceReader(dataDirectory, config).readAuthMeRows()
            }

            assertTrue(exception.message!!.contains("未找到 AuthMe SQLite 数据库文件"))
            assertTrue(exception.message!!.contains("source.sqlite.path / source.sqlite.jdbcUrl"))
            assertTrue(exception.message!!.contains("避免 SQLite 自动创建空库"))
        } finally {
            Files.walk(dataDirectory)
                .sorted(Comparator.reverseOrder())
                .forEach(Files::deleteIfExists)
        }
    }
}
