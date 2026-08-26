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

package icu.h2l.login.cli.download

import com.google.gson.JsonParser
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Client for the PaperMC download API (`https://api.papermc.io/v2/projects`).
 *
 * Supports any PaperMC project (e.g. `paper`, `velocity`). Downloads display
 * a live progress bar that updates in-place when stdout is a terminal.
 *
 * ## Typical usage
 * ```
 * val api = PaperMcDownloader()
 * val version = api.resolveVersion("paper", "latest")
 * val dest    = api.downloadLatestBuild("paper", version, targetDir)
 * ```
 */
class PaperMcDownloader {

    private val http: HttpClient =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build()

    // ----------------------------------------------------------------- public --

    /**
     * Returns all available versions for [project], ordered oldest → newest.
     * Example result for `paper`: `["1.8.8", …, "1.21.4"]`
     */
    fun fetchVersions(project: String): List<String> {
        val body = get("$API_BASE/$project")
        val json = JsonParser.parseString(body).asJsonObject
        return json.getAsJsonArray("versions").map { it.asString }
    }

    /**
     * Resolves a version string for [project]:
     * - `null` or `"latest"` → the newest version from the API
     * - anything else       → validated; throws [IllegalArgumentException] if not found
     */
    fun resolveVersion(project: String, requested: String?): String {
        val versions = fetchVersions(project)
        if (requested == null || requested.equals("latest", ignoreCase = true)) {
            return versions.last()
        }
        require(requested in versions) {
            val available = versions.takeLast(20).joinToString(", ")
            "$project version '$requested' not found.\n  Latest 20 available: $available"
        }
        return requested
    }

    /**
     * Downloads the latest build of [version] for [project] into [targetDir].
     *
     * The file is named `{project}-{version}-{build}.jar` (e.g. `paper-1.21.4-146.jar`).
     * If the file already exists it is **not** re-downloaded unless [overwrite] is `true`.
     *
     * @return the (possibly already-existing) jar [File]
     */
    fun downloadLatestBuild(
        project: String,
        version: String,
        targetDir: File,
        overwrite: Boolean = false,
    ): File {
        targetDir.mkdirs()
        val info = fetchLatestBuildInfo(project, version)
        val dest = targetDir.resolve(info.fileName)

        if (dest.exists() && !overwrite) {
            println("  [exists] ${dest.name}  (skipping — use --overwrite to re-download)")
            return dest
        }

        val url = "$API_BASE/$project/versions/$version/builds/${info.buildNumber}/downloads/${info.fileName}"
        downloadWithProgress(url, dest, "${info.fileName}")
        return dest
    }

    /**
     * Fetches build metadata for the latest build of [version] in [project].
     */
    fun fetchLatestBuildInfo(project: String, version: String): BuildInfo {
        val body = get("$API_BASE/$project/versions/$version/builds")
        val json = JsonParser.parseString(body).asJsonObject
        val builds = json.getAsJsonArray("builds")
        check(builds.size() > 0) { "No builds found for $project $version" }

        // The API returns builds sorted ascending; the last entry is the newest.
        val latest = builds[builds.size() - 1].asJsonObject
        val buildNumber = latest.get("build").asInt
        val channel = latest.get("channel")?.asString ?: "default"
        val fileName =
            latest
                .getAsJsonObject("downloads")
                .getAsJsonObject("application")
                .get("name")
                .asString
        return BuildInfo(buildNumber, fileName, version, channel)
    }

    // ----------------------------------------------------------------- private --

    private fun downloadWithProgress(url: String, dest: File, label: String) {
        val tmpFile = File(dest.parent, "${dest.name}.tmp")
        try {
            val request =
                HttpRequest
                    .newBuilder(URI.create(url))
                    .GET()
                    .timeout(Duration.ofMinutes(10))
                    .build()

            // Use InputStream body handler so we can track progress ourselves.
            val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
            check(response.statusCode() == 200) {
                "HTTP ${response.statusCode()} — failed to download $url"
            }

            val totalBytes = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
            val isTty = System.console() != null

            var downloadedBytes = 0L
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

            tmpFile.outputStream().buffered().use { out ->
                response.body().use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n == -1) break
                        out.write(buffer, 0, n)
                        downloadedBytes += n
                        printProgress(label, downloadedBytes, totalBytes, isTty)
                    }
                }
            }

            // Final newline to leave the progress bar on screen.
            println()

            tmpFile.renameTo(dest)
            val sizeKb = dest.length() / 1024
            println("  [ok]     $label  (${sizeKb} KB)")
        } catch (e: Exception) {
            tmpFile.delete()
            throw e
        }
    }

    private fun printProgress(label: String, downloaded: Long, total: Long, isTty: Boolean) {
        val pct = if (total > 0) (downloaded * 100 / total).toInt() else -1
        val dlMb = "%.1f MB".format(downloaded / 1_048_576.0)
        val totalMb = if (total > 0) " / %.1f MB".format(total / 1_048_576.0) else ""

        val bar =
            if (pct >= 0) {
                val filled = pct / 5   // 20-char bar
                val empty = 20 - filled
                "[" + "=".repeat(filled) + (if (filled < 20) ">" else "") + " ".repeat(
                    (empty - 1).coerceAtLeast(0),
                ) + "]  $pct%"
            } else {
                "[downloading…]    "
            }

        val line = "  $bar   $dlMb$totalMb"
        if (isTty) {
            print("\r$line")
            System.out.flush()
        } else {
            // Non-TTY (CI / piped): print a line every ~5 MB to avoid silence.
            val fiveMb = 5 * 1_048_576L
            if (downloaded % fiveMb < DEFAULT_BUFFER_SIZE) {
                println("  [progress] $label  $dlMb$totalMb")
            }
        }
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
            "PaperMC API error: HTTP ${response.statusCode()} from $url"
        }
        return response.body()
    }

    // -----------------------------------------------------------------  types --

    /**
     * Metadata for a single PaperMC build.
     *
     * @property buildNumber  The numeric build identifier (e.g. 146).
     * @property fileName     The canonical jar filename (e.g. `paper-1.21.4-146.jar`).
     * @property version      The game / proxy version string (e.g. `1.21.4`).
     * @property channel      Release channel — typically `"default"` or `"experimental"`.
     */
    data class BuildInfo(
        val buildNumber: Int,
        val fileName: String,
        val version: String,
        val channel: String,
    )

    companion object {
        private const val API_BASE = "https://api.papermc.io/v2/projects"
    }
}

