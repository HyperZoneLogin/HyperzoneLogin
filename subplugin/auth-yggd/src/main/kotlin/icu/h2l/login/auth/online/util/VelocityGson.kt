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

package icu.h2l.login.auth.online.util

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.reflect.TypeToken
import com.velocitypowered.api.util.GameProfile
import java.lang.reflect.Type
import java.util.UUID

/**
 * 运行时 Gson 实例，与 `VelocityServer.GENERAL_GSON` 兼容。
 *
 * **获取策略（优先级从高到低）：**
 * 1. 反射读取 `com.velocitypowered.proxy.VelocityServer.GENERAL_GSON`——直接复用 Velocity 内置实例，
 *    行为与 Velocity 完全一致（包含 `FaviconSerializer` 等全部注册项）。
 * 2. 自行构造的 [Gson]，注册 [HzlGameProfileSerializer]——仅在宿主 Velocity 类无法反射到时作为安全兜底。
 *
 * 为什么不能直接用 `Gson()`？
 * - Mojang / Yggdrasil API 的 `id` 字段是**无连字符 UUID**（如 `550e8400e29b41d4a716446655440000`），
 *   普通 Gson 无法正确反序列化 `GameProfile`。
 * - `GameProfile` 没有无参构造器，Gson 无法通过默认机制实例化它。
 */
object VelocityGson {

    /**
     * 与 `VelocityServer.GENERAL_GSON` 行为等价的 [Gson] 实例。
     */
    val INSTANCE: Gson by lazy {
        runCatching {
            Class.forName("com.velocitypowered.proxy.VelocityServer")
                .getField("GENERAL_GSON")
                .get(null) as Gson
        }.getOrElse {
            // 反射失败时（例如单元测试环境）使用内置实现
            GsonBuilder()
                .registerTypeHierarchyAdapter(GameProfile::class.java, HzlGameProfileSerializer)
                .create()
        }
    }
}

/**
 * 与 `com.velocitypowered.proxy.protocol.util.GameProfileSerializer` 等价的序列化器。
 *
 * Mojang / Yggdrasil API 使用**无连字符 UUID 字符串**（32 位十六进制）作为 `id` 字段，
 * 本实现与 Velocity 源码保持完全一致的序列化行为。
 *
 * 参考：`ref/Velocity/.../GameProfileSerializer.java`
 */
internal object HzlGameProfileSerializer : JsonSerializer<GameProfile>, JsonDeserializer<GameProfile> {

    private val propertyListType: Type = object : TypeToken<List<GameProfile.Property>>() {}.type

    // ── 反序列化：Yggdrasil 响应 → GameProfile ──────────────────────────────

    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): GameProfile {
        val obj = json.asJsonObject
        // id 字段为无连字符 UUID 字符串，需手动补全格式
        val uuid = parseUndashedUuid(obj.get("id").asString)
        val name = obj.get("name").asString
        val properties: List<GameProfile.Property> =
            context.deserialize(obj.get("properties"), propertyListType) ?: emptyList()
        return GameProfile(uuid, name, properties)
    }

    // ── 序列化：GameProfile → JSON ───────────────────────────────────────────

    override fun serialize(
        src: GameProfile,
        typeOfSrc: Type,
        context: JsonSerializationContext,
    ): JsonElement {
        val obj = JsonObject()
        obj.add("id", JsonPrimitive(src.undashedId))
        obj.add("name", JsonPrimitive(src.name))
        obj.add("properties", context.serialize(src.properties, propertyListType))
        return obj
    }

    // ── 内部工具 ─────────────────────────────────────────────────────────────

    /**
     * 将 32 位无连字符十六进制 UUID 字符串（如 `550e8400e29b41d4a716446655440000`）
     * 转换为标准 [UUID]（含连字符格式）。
     */
    private fun parseUndashedUuid(undashed: String): UUID {
        require(undashed.length == 32) {
            "Invalid undashed UUID length ${undashed.length}: '$undashed'"
        }
        // 在 8-4-4-4-12 位置插入连字符
        val dashed = buildString(36) {
            append(undashed, 0, 8); append('-')
            append(undashed, 8, 12); append('-')
            append(undashed, 12, 16); append('-')
            append(undashed, 16, 20); append('-')
            append(undashed, 20, 32)
        }
        return UUID.fromString(dashed)
    }

    /**
     * 获取 [GameProfile] 的无连字符 UUID 字符串表示。
     * Velocity API 的 `getUndashedId()` 已提供，直接调用；
     * 回退时手动去除连字符。
     */
    private val GameProfile.undashedId: String
        get() = runCatching { this.getUndashedId() }.getOrElse {
            id.toString().replace("-", "")
        }
}


