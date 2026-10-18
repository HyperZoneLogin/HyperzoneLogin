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

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

/**
 * 用给定的 [ProguardMappings] 反混淆一个 jar（类名 + 字段 + 方法 + 栈帧类型），
 * 输出到目标路径。非 class 条目（资源、库等）原样透传，跳过签名文件与 MANIFEST。
 */
object JarRemapper {

    fun remap(source: Path, target: Path, mappings: ProguardMappings) {
        val superTypes = buildHierarchy(source)
        val remapper = mappings.remapper(superTypes)

        JarFile(source.toFile()).use { input ->
            JarOutputStream(Files.newOutputStream(target)).use { output ->
                for (entry in input.entries().toList()) {
                    val name = entry.name
                    if (isSignatureOrManifest(name)) continue

                    val bytes = input.getInputStream(entry).use { it.readBytes() }
                    val (outName, outBytes) = if (name.endsWith(".class")) {
                        remapClass(bytes, remapper)
                    } else {
                        name to bytes
                    }
                    output.putNextEntry(JarEntry(outName))
                    output.write(outBytes)
                    output.closeEntry()
                }
            }
        }
    }

    private fun isSignatureOrManifest(name: String): Boolean =
        name.startsWith("META-INF/") &&
            (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA") ||
                name == "META-INF/MANIFEST.MF")

    private fun buildHierarchy(source: Path): Map<String, List<String>> {
        val map = HashMap<String, List<String>>()
        JarFile(source.toFile()).use { input ->
            for (entry in input.entries().toList()) {
                if (!entry.name.endsWith(".class")) continue
                val bytes = input.getInputStream(entry).use { it.readBytes() }
                val reader = ClassReader(bytes)
                val supers = mutableListOf<String>()
                if (reader.superName != null && reader.superName != "java/lang/Object") {
                    supers.add(reader.superName)
                }
                supers.addAll(reader.interfaces.toList())
                if (supers.isNotEmpty()) map[reader.className] = supers
            }
        }
        return map
    }

    private fun remapClass(bytes: ByteArray, remapper: Remapper): Pair<String, ByteArray> {
        val reader = ClassReader(bytes)
        val writer = ClassWriter(0)
        reader.accept(ClassRemapper(writer, remapper), 0)
        val remapped = writer.toByteArray()
        val newName = ClassReader(remapped).className + ".class"
        return newName to remapped
    }
}
