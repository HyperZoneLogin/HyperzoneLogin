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

package icu.h2l.login.codecgen

import org.objectweb.asm.commons.Remapper
import java.nio.file.Files
import java.nio.file.Path

/**
 * 解析 Mojang 官方 `server.txt`（proguard 风格映射，`official -> obfuscated` 方向），
 * 反向得到 `obfuscated -> official` 的映射，供 ASM 反混淆官方 server.jar。
 */
class ProguardMappings(private val file: Path) {

    // obf 短名 -> official 内部名（用 `/` 分隔）
    private val obfToOfficial = HashMap<String, String>()

    // official FQN -> obf 短名
    private val officialToObf = HashMap<String, String>()

    // obf owner -> (obf 字段名 -> official 字段名)
    private val fields = HashMap<String, HashMap<String, String>>()

    // obf owner -> (obf 方法名 + obf 描述符 -> official 方法名)
    private val methods = HashMap<String, HashMap<String, String>>()

    init {
        parse()
    }

    fun remapper(superTypes: Map<String, List<String>>): Remapper = object : Remapper() {
        override fun map(internalName: String): String = obfToOfficial[internalName] ?: internalName

        override fun mapFieldName(owner: String, name: String, descriptor: String): String =
            resolve(owner) { current -> fields[current]?.get(name) } ?: name

        override fun mapMethodName(owner: String, name: String, descriptor: String): String =
            resolve(owner) { current -> methods[current]?.get(name + descriptor) } ?: name

        private fun resolve(owner: String, lookup: (String) -> String?): String? {
            val seen = HashSet<String>()
            val queue = ArrayDeque<String>()
            queue.add(owner)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                if (!seen.add(current)) continue
                lookup(current)?.let { return it }
                superTypes[current]?.forEach { queue.add(it) }
            }
            return null
        }
    }

    private fun parse() {
        val lines = Files.readAllLines(file)

        // 第一遍：类映射
        for (line in lines) {
            val trimmed = line.trimStart()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val classMatch = CLASS_LINE.matchEntire(trimmed) ?: continue
            val official = classMatch.groupValues[1]
            val obf = classMatch.groupValues[2]
            obfToOfficial[obf] = official.replace('.', '/')
            officialToObf[official] = obf
        }

        // 第二遍：字段与方法
        var currentOwner: String? = null
        for (line in lines) {
            val trimmed = line.trimStart()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            val classMatch = CLASS_LINE.matchEntire(trimmed)
            if (classMatch != null) {
                currentOwner = classMatch.groupValues[2]
                continue
            }
            if (currentOwner == null) continue

            val fieldMatch = FIELD_LINE.matchEntire(trimmed)
            if (fieldMatch != null) {
                val officialName = fieldMatch.groupValues[2]
                val obfName = fieldMatch.groupValues[3]
                fields.getOrPut(currentOwner) { HashMap() }[obfName] = officialName
                continue
            }

            val methodMatch = METHOD_LINE.matchEntire(trimmed)
            if (methodMatch != null) {
                val returnType = methodMatch.groupValues[3]
                val methodName = methodMatch.groupValues[4]
                val params = methodMatch.groupValues[5]
                val obfName = methodMatch.groupValues[6]

                val descriptor = buildObfDescriptor(returnType, params)
                methods.getOrPut(currentOwner) { HashMap() }[obfName + descriptor] = methodName
            }
        }
    }

    private fun buildObfDescriptor(returnType: String, params: String): String {
        val paramTypes = if (params.isBlank()) emptyList() else params.split(',').map { it.trim() }
        return buildString {
            append('(')
            for (p in paramTypes) append(typeToObfDescriptor(p))
            append(')')
            append(typeToObfDescriptor(returnType))
        }
    }

    private fun typeToObfDescriptor(type: String): String = when (type) {
        "void" -> "V"
        "int" -> "I"
        "boolean" -> "Z"
        "long" -> "J"
        "float" -> "F"
        "double" -> "D"
        "byte" -> "B"
        "short" -> "S"
        "char" -> "C"
        else -> when {
            type.endsWith("[]") -> "[" + typeToObfDescriptor(type.dropLast(2))
            else -> {
                val obf = officialToObf[type]
                if (obf != null) "L$obf;" else "L${type.replace('.', '/')};"
            }
        }
    }

    companion object {
        // official.FQN -> obfName:
        private val CLASS_LINE = Regex("""^([\w.$]+) -> ([\w$]+):$""")

        //     official.Type fieldName -> obfName
        private val FIELD_LINE = Regex("""^([\w.$\[\]]+) ([\w$<>]+) -> ([\w$]+)$""")

        //     [line:line:]official.ReturnType methodName(params) -> obfName
        private val METHOD_LINE = Regex(
            """^(?:(\d+):(\d+):)?([\w.$\[\]]+) ([\w$<>]+)\((.*)\) -> ([\w$]+)$""",
        )
    }
}
