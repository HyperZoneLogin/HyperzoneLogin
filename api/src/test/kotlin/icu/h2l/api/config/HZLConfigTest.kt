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

package icu.h2l.api.config

import icu.h2l.api.util.ConfigLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.spongepowered.configurate.objectmapping.ConfigSerializable
import org.spongepowered.configurate.objectmapping.meta.Comment
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class HZLConfigTest {

    @ConfigSerializable
    data class TestConfig(
        @Comment("test.foo")
        val foo: String = "default-foo",
        @Comment("test.bar")
        val bar: Boolean = true,
        @Comment("test.nested")
        val nested: Nested = Nested(),
    ) {
        @ConfigSerializable
        data class Nested(
            @Comment("test.nested.value")
            val value: Int = 42,
        )
    }

    @TempDir
    lateinit var tempDir: Path

    private fun newConfig(): HZLConfig<TestConfig> =
        object : HZLConfig<TestConfig>(tempDir, "test.conf", { TestConfig() }) {}

    @Test
    fun `reads values by comment id`() {
        ConfigLoader.loadConfig<TestConfig>(
            dataDirectory = tempDir,
            fileName = "test.conf",
            defaultProvider = { TestConfig() },
        )
        val config = newConfig()
        config.reload()

        assertEquals("default-foo", config.get("test.foo"))
        assertEquals(true, config.get("test.bar"))
        assertEquals(42, config.get("test.nested.value"))
    }

    @Test
    fun `set writes value and refreshes cache`() {
        ConfigLoader.loadConfig<TestConfig>(
            dataDirectory = tempDir,
            fileName = "test.conf",
            defaultProvider = { TestConfig() },
        )
        val config = newConfig()
        config.reload()

        assertTrue(config.contains("test.bar"))
        config.set("test.bar", false)

        assertFalse(config.get("test.bar") as Boolean)
        // 重新从磁盘加载后仍应保持新值
        val fresh = newConfig()
        fresh.reload()
        assertFalse(fresh.get("test.bar") as Boolean)
    }

    @Test
    fun `reload reflects external file changes`() {
        ConfigLoader.loadConfig<TestConfig>(
            dataDirectory = tempDir,
            fileName = "test.conf",
            defaultProvider = { TestConfig() },
        )
        val config = newConfig()
        config.reload()
        assertEquals("default-foo", config.get("test.foo"))

        // 外部改写文件后 reload 应拿到新值
        config.set("test.foo", "changed-foo")
        assertEquals("changed-foo", config.get("test.foo"))
    }

    @Test
    fun `registry registers and retrieves by name`() {
        ConfigLoader.loadConfig<TestConfig>(
            dataDirectory = tempDir,
            fileName = "test.conf",
            defaultProvider = { TestConfig() },
        )
        val config = newConfig()
        config.reload()
        HZLConfigRegistry.register("test", config)

        assertNotNull(HZLConfigRegistry.get("test"))
        assertEquals("default-foo", HZLConfigRegistry.get("test")?.get("test.foo"))
    }

    @Test
    fun `regenerate keeps old values, drops extra keys, adds missing defaults`() {
        // 写入一份「旧版本」配置：缺少 bar、多出 obsolete 键。
        Files.writeString(
            tempDir.resolve("test.conf"),
            """
            foo="old-foo"
            obsolete="remove-me"
            nested {
                value=7
            }
            """.trimIndent(),
            StandardCharsets.UTF_8,
        )

        val config = newConfig()
        config.reload()
        config.regenerate()

        // 旧值保留
        assertEquals("old-foo", config.get("test.foo"))
        assertEquals(7, config.get("test.nested.value"))
        // 缺失的默认值补齐
        assertEquals(true, config.get("test.bar"))
        // 多余键被删除
        val fileText = Files.readString(tempDir.resolve("test.conf"))
        assertFalse(fileText.contains("obsolete"), "多余键应被移除")
    }
}
