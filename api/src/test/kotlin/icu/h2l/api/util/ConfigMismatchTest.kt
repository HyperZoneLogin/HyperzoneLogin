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

package icu.h2l.api.util

import icu.h2l.api.log.HyperZoneLogApi
import icu.h2l.api.log.HyperZoneLogger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.spongepowered.configurate.objectmapping.ConfigSerializable
import org.spongepowered.configurate.objectmapping.meta.Comment
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * 验证 [ConfigLoader] 的 schema 不一致检测（缺失/多余键）能正确触发 warn。
 */
class ConfigMismatchTest {

    @ConfigSerializable
    data class ProbeConfig(
        @Comment("probe.a")
        val a: String = "a-default",
        @Comment("probe.b")
        val b: Boolean = true,
        @Comment("probe.nested")
        val nested: Nested = Nested(),
    ) {
        @ConfigSerializable
        data class Nested(
            @Comment("probe.nested.x")
            val x: Int = 1,
            @Comment("probe.nested.y")
            val y: String = "y-default",
        )
    }

    @TempDir
    lateinit var tempDir: Path

    private val warns = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        warns.clear()
        HyperZoneLogApi.registerLogger(object : HyperZoneLogger {
            override fun info(message: String) = Unit
            override fun debug(message: String) = Unit
            override fun warn(message: String) { warns.add(message) }
            override fun error(message: String, throwable: Throwable?) = Unit
        })
    }

    @AfterEach
    fun tearDown() {
        HyperZoneLogApi.registerLogger(object : HyperZoneLogger {
            override fun info(message: String) = Unit
            override fun debug(message: String) = Unit
            override fun warn(message: String) = Unit
            override fun error(message: String, throwable: Throwable?) = Unit
        })
    }

    @Test
    fun `warns when keys are missing`() {
        Files.writeString(tempDir.resolve("probe.conf"), "a=\"custom-a\"\n", StandardCharsets.UTF_8)

        ConfigLoader.loadConfig<ProbeConfig>(
            dataDirectory = tempDir,
            fileName = "probe.conf",
            defaultProvider = { ProbeConfig() },
        )

        assertTrue(warns.any { it.contains("缺失键") && it.contains("b") }, "应当警告缺失的 b 键，实际警告: $warns")
    }

    @Test
    fun `warns when extra keys are present`() {
        Files.writeString(
            tempDir.resolve("probe.conf"),
            "a=\"custom-a\"\nb=true\nobsolete=\"remove-me\"\n",
            StandardCharsets.UTF_8,
        )

        ConfigLoader.loadConfig<ProbeConfig>(
            dataDirectory = tempDir,
            fileName = "probe.conf",
            defaultProvider = { ProbeConfig() },
        )

        assertTrue(warns.any { it.contains("多余键") && it.contains("obsolete") }, "应当警告多余的 obsolete 键，实际警告: $warns")
    }

    @Test
    fun `does not warn when schema matches`() {
        Files.writeString(
            tempDir.resolve("probe.conf"),
            "a=\"custom-a\"\nb=true\nnested {\n  x=5\n  y=\"custom-y\"\n}\n",
            StandardCharsets.UTF_8,
        )

        ConfigLoader.loadConfig<ProbeConfig>(
            dataDirectory = tempDir,
            fileName = "probe.conf",
            defaultProvider = { ProbeConfig() },
        )

        assertFalse(warns.any { it.contains("schema 不一致") }, "schema 一致时不应警告，实际警告: $warns")
    }

    @Test
    fun `warns for missing nested keys`() {
        // 缺少顶层 b，且嵌套里缺少 y、多出 obsolete
        Files.writeString(
            tempDir.resolve("probe.conf"),
            "a=\"custom-a\"\nnested {\n  x=5\n  obsolete=1\n}\n",
            StandardCharsets.UTF_8,
        )

        ConfigLoader.loadConfig<ProbeConfig>(
            dataDirectory = tempDir,
            fileName = "probe.conf",
            defaultProvider = { ProbeConfig() },
        )

        val warn = warns.firstOrNull { it.contains("schema 不一致") } ?: error("应当警告，实际: $warns")
        assertTrue(warn.contains("b"), "应报告顶层缺失键 b：$warn")
        assertTrue(warn.contains("nested.y"), "应报告嵌套缺失键 nested.y：$warn")
        assertTrue(warn.contains("nested.obsolete"), "应报告嵌套多余键 nested.obsolete：$warn")
    }
}
