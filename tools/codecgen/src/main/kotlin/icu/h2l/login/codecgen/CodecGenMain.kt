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

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.tools.ToolProvider

/**
 * backend-hzlimbo 的 codec 一键生成工具。
 *
 * 用法：
 *   codecgen <version> [--jar <path>] [--key <key>] [--output <dir>]
 *
 * 流程：下载/解包官方 server.jar → 用 Mojang server.txt 反混淆 → 运行时编译内置 harness →
 * 引导 RegistryAccess 序列化 registry codec + tags → 写出 `codec_<key>.nbt` / `tags_<key>.nbt`。
 */
fun main(args: Array<String>) {
    var version: String? = null
    var jarPath: String? = null
    var key: String? = null
    var output: String? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--jar" -> jarPath = args[++i]
            "--key" -> key = args[++i]
            "--output" -> output = args[++i]
            else -> if (!args[i].startsWith("--")) version = args[i]
        }
        i++
    }

    val outputDir = output?.let(Path::of)
        ?: Path.of("subplugin/backend-hzlimbo/src/main/resources/dimension")

    val workDir = Path.of(System.getProperty("java.io.tmpdir"), "hzlimbo-codecgen").resolve(version ?: "local")
    Files.createDirectories(workDir)

    val serverJar = if (jarPath != null) {
        ServerJarResolver.resolveFromBundler(Path.of(jarPath), workDir)
    } else {
        ServerJarResolver.resolve(requireNotNull(version) { "需要 version 或 --jar" }, workDir)
    }

    val resolvedKey = key ?: version?.replace('.', '_') ?: error("需要 --key 或 version")

    val mappedJar = workDir.resolve("server-mapped.jar")
    println("Remapping obfuscated server.jar ...")
    JarRemapper.remap(serverJar.nestedJar, mappedJar, ProguardMappings(serverJar.mappings))

    val classesDir = workDir.resolve("classes")
    Files.createDirectories(classesDir)
    val classpath = (listOf(mappedJar) + serverJar.libraries + serverJar.versionJson.parent)
        .joinToString(System.getProperty("path.separator"))

    println("Compiling harness ...")
    compileHarness(classpath, classesDir)

    val codecOut = workDir.resolve("codec.nbt")
    val tagsOut = workDir.resolve("tags.nbt")
    println("Running harness (this boots vanilla RegistryAccess) ...")
    runHarness(classpath + System.getProperty("path.separator") + classesDir, codecOut, tagsOut)

    Files.createDirectories(outputDir)
    Files.copy(codecOut, outputDir.resolve("codec_$resolvedKey.nbt"), StandardCopyOption.REPLACE_EXISTING)
    Files.copy(tagsOut, outputDir.resolve("tags_$resolvedKey.nbt"), StandardCopyOption.REPLACE_EXISTING)
    println("Done: ${outputDir.resolve("codec_$resolvedKey.nbt")} (${Files.size(codecOut)} bytes)")
    println("      ${outputDir.resolve("tags_$resolvedKey.nbt")} (${Files.size(tagsOut)} bytes)")
}

private fun compileHarness(classpath: String, classesDir: Path) {
    val harnessSource = ProguardMappings::class.java.getResourceAsStream("/harness/CodecDump.java")
        ?: error("Missing bundled CodecDump.java")
    val sourceDir = Files.createTempDirectory("harness-src")
    val sourceFile = sourceDir.resolve("CodecDump.java")
    harnessSource.use { Files.copy(it, sourceFile, StandardCopyOption.REPLACE_EXISTING) }

    val compiler = ToolProvider.getSystemJavaCompiler()
    val result = compiler.run(null, null, null, "-cp", classpath, "-d", classesDir.toString(), sourceFile.toString())
    check(result == 0) { "Harness 编译失败 (exit $result)" }
}

private fun runHarness(classpath: String, codecOut: Path, tagsOut: Path) {
    val process = ProcessBuilder(
        "java", "-cp", classpath, "CodecDump", codecOut.toString(), tagsOut.toString(),
    ).inheritIO().start()
    check(process.waitFor() == 0) { "Harness 运行失败" }
}
