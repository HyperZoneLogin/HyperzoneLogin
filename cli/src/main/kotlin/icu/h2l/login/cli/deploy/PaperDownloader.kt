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

package icu.h2l.login.cli.deploy

import com.google.gson.JsonParser
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Downloads Paper server jars from the official PaperMC API.
 *
 * API base: https://api.papermc.io/v2/projects/paper
 */
object PaperDownloader {

    private const val API_BASE = "https://api.papermc.io/v2/projects/paper"

    private val http: HttpClient =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build()

    /** Returns the full list of available Paper versions (oldest → newest). */
    fun fetchVersions(): List<String> {
        val body = get(API_BASE)
        val json = JsonParser.parseString(body).asJsonObject
        return json.getAsJsonArray("versions").map { it.asString }
    }

    /**
     * Resolves a version string:
     * - `null` or `"latest"` → the newest stable version from the API
     * - anything else → validated against the version list, error if absent
     */
    fun resolveVersion(requested: String?): String {
        val versions = fetchVersions()
        if (requested == null || requested.equals("latest", ignoreCase = true)) {
            return versions.last()
        }
        require(requested in versions) {
            "Paper version '$requested' not found. Available versions:\n  ${versions.joinToString(", ")}"
        }
        return requested
    }

    /**
     * Downloads the latest build of [version] into [targetDir] as
     * `paper-{version}-{build}.jar`. If the file already exists it is skipped.
     *
     * @return the downloaded (or already-present) jar file
     */
    fun downloadLatestBuild(version: String, targetDir: File): File {
        targetDir.mkdirs()

        val (buildNumber, fileName) = fetchLatestBuild(version)
        val dest = targetDir.resolve(fileName)

        if (dest.exists()) {
            println("  [exists] ${dest.path}  (already downloaded)")
            return dest
        }

        val downloadUrl = "$API_BASE/versions/$version/builds/$buildNumber/downloads/$fileName"
        println("  [download] Paper $version build $buildNumber → ${dest.path}")
        println("             $downloadUrl")

        val request =
            HttpRequest
                .newBuilder(URI.create(downloadUrl))
                .GET()
                .timeout(Duration.ofMinutes(10))
                .build()

        val tmpFile = File(dest.parent, "${dest.name}.tmp")
        try {
            val response = http.send(request, HttpResponse.BodyHandlers.ofFile(tmpFile.toPath()))
            check(response.statusCode() == 200) {
                "Failed to download Paper jar: HTTP ${response.statusCode()} from $downloadUrl"
            }
            tmpFile.renameTo(dest)
            println("  [ok]     Downloaded ${dest.name} (${dest.length() / 1024} KB)")
        } catch (e: Exception) {
            tmpFile.delete()
            throw e
        }

        return dest
    }

    // ---------------------------------------------------------------- private --

    /** Returns Pair(buildNumber, jarFileName) for the latest build of [version]. */
    private fun fetchLatestBuild(version: String): Pair<Int, String> {
        val body = get("$API_BASE/versions/$version/builds")
        val json = JsonParser.parseString(body).asJsonObject
        val builds = json.getAsJsonArray("builds")
        check(builds.size() > 0) { "No builds found for Paper $version" }

        val latest = builds[builds.size() - 1].asJsonObject
        val buildNumber = latest.get("build").asInt
        val fileName =
            latest
                .getAsJsonObject("downloads")
                .getAsJsonObject("application")
                .get("name")
                .asString
        return buildNumber to fileName
    }

    private fun get(url: String): String {
        val request =
            HttpRequest
                .newBuilder(URI.create(url))
                .GET()
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) {
            "PaperMC API request failed: HTTP ${response.statusCode()} from $url"
        }
        return response.body()
    }
}
