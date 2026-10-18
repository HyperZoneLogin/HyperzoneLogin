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

import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.jar.JarFile

/**
 * 下载并解包指定 Minecraft 版本的官方 server.jar。
 *
 * 官方 server.jar 是「bundler」格式：真正的服务端类在 `META-INF/versions/<version>/server-<version>.jar`（混淆），
 * 依赖库在 `META-INF/libraries/`，`version.json` 在根目录。
 */
object ServerJarResolver {

    private const val VERSION_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

    private val httpClient: HttpClient = HttpClient.newHttpClient()

    data class ServerJar(
        val bundlerJar: Path,
        val nestedJar: Path,
        val libraries: List<Path>,
        val versionJson: Path,
        val mappings: Path,
    )

    /** 依据版本号下载并解包，返回各部分路径。所有产物缓存于 [workDir]。 */
    fun resolve(version: String, workDir: Path): ServerJar {
        val bundlerJar = workDir.resolve("server-$version.jar")
        val mappings = workDir.resolve("server-$version.txt")
        if (!Files.exists(bundlerJar) || !Files.exists(mappings)) {
            download(version, bundlerJar, mappings)
        }

        val nestedJar = workDir.resolve("server-$version-nested.jar")
        val libsDir = workDir.resolve("libs")
        val versionJson = workDir.resolve("version.json")

        if (!Files.exists(nestedJar)) {
            extractNestedJar(bundlerJar, nestedJar)
        }
        if (!Files.exists(versionJson)) {
            extractEntry(bundlerJar, "version.json", versionJson)
        }
        val libraries = extractLibraries(bundlerJar, libsDir)

        return ServerJar(bundlerJar, nestedJar, libraries, versionJson, mappings)
    }

    /** 本地 bundler jar：解包嵌套服务端 jar / 依赖库 / version.json；映射文件取同目录 server.txt。 */
    fun resolveFromBundler(bundlerJar: Path, workDir: Path): ServerJar {
        val nestedJar = workDir.resolve("server-local-nested.jar")
        val libsDir = workDir.resolve("libs")
        val versionJson = workDir.resolve("version.json")
        extractNestedJar(bundlerJar, nestedJar)
        extractEntry(bundlerJar, "version.json", versionJson)
        val libraries = extractLibraries(bundlerJar, libsDir)
        val mappings = bundlerJar.resolveSibling("server.txt")
        check(Files.exists(mappings)) { "本地 jar 需要同目录提供 server.txt 映射文件" }
        return ServerJar(bundlerJar, nestedJar, libraries, versionJson, mappings)
    }

    private fun download(version: String, jarOut: Path, mappingsOut: Path) {
        val manifest = httpGet(VERSION_MANIFEST_URL)
        val manifestJson = JsonParser.parseString(manifest).asJsonObject
        val versionEntry = manifestJson.getAsJsonArray("versions").firstOrNull {
            it.asJsonObject.get("id").asString == version
        } ?: error("Unknown Minecraft version: $version")
        val versionJsonUrl = versionEntry.asJsonObject.get("url").asString

        val versionJson = JsonParser.parseString(httpGet(versionJsonUrl)).asJsonObject
        val downloads = versionJson.getAsJsonObject("downloads")
        val serverUrl = downloads.getAsJsonObject("server").get("url").asString
        val mappingsUrl = downloads.getAsJsonObject("server_mappings").get("url").asString

        println("Downloading server.jar for $version ...")
        downloadFile(serverUrl, jarOut)
        println("Downloading server mappings ...")
        downloadFile(mappingsUrl, mappingsOut)
    }

    private fun extractNestedJar(bundlerJar: Path, out: Path) {
        JarFile(bundlerJar.toFile()).use { jar ->
            val entry = jar.entries().asSequence()
                .firstOrNull { it.name.startsWith("META-INF/versions/") && it.name.endsWith(".jar") }
                ?: error("No nested server jar found in $bundlerJar")
            jar.getInputStream(entry).use { input ->
                Files.copy(input, out, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun extractEntry(jarPath: Path, entryName: String, out: Path) {
        JarFile(jarPath.toFile()).use { jar ->
            val entry = jar.getJarEntry(entryName) ?: error("Missing $entryName in $jarPath")
            jar.getInputStream(entry).use { input ->
                Files.copy(input, out, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun extractLibraries(bundlerJar: Path, libsDir: Path): List<Path> {
        Files.createDirectories(libsDir)
        val result = mutableListOf<Path>()
        JarFile(bundlerJar.toFile()).use { jar ->
            for (entry in jar.entries().asSequence()) {
                if (!entry.name.startsWith("META-INF/libraries/") || !entry.name.endsWith(".jar")) continue
                val fileName = entry.name.substringAfterLast('/')
                val out = libsDir.resolve(fileName)
                if (!Files.exists(out)) {
                    jar.getInputStream(entry).use { input ->
                        Files.copy(input, out, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
                result.add(out)
            }
        }
        return result
    }

    private fun httpGet(url: String): String {
        val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "HTTP ${response.statusCode()} for $url" }
        return response.body()
    }

    private fun downloadFile(url: String, out: Path) {
        Files.createDirectories(out.parent)
        val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        check(response.statusCode() == 200) { "HTTP ${response.statusCode()} for $url" }
        response.body().use { input ->
            Files.copy(input, out, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
