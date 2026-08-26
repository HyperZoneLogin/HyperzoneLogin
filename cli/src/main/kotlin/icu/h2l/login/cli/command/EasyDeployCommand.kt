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

package icu.h2l.login.cli.command

import icu.h2l.login.cli.deploy.GameDeployer
import icu.h2l.login.cli.deploy.LobbyDeployer
import icu.h2l.login.cli.deploy.ScriptGenerator
import icu.h2l.login.cli.deploy.VelocityDeployer
import icu.h2l.login.cli.download.PaperMcDownloader
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.io.File
import java.security.SecureRandom
import java.util.Base64

@Command(
    name = "easydeploy",
    mixinStandardHelpOptions = true,
    description = [
        "Automatically deploy a full HyperZoneLogin server stack in the current directory.",
        "",
        "Creates three sub-directories:",
        "  velocity/  \u2014 Velocity proxy (public entry point)",
        "  lobby/     \u2014 Lobby / pre-auth Paper server (outpre-auth backend)",
        "  game/      \u2014 Game Paper server (play backend)",
        "",
        "Config files are generated for all three servers and server JARs are",
        "automatically downloaded from https://api.papermc.io unless disabled.",
        "Velocity uses modern forwarding; the generated secret is shared to both backends.",
    ],
)
class EasyDeployCommand : Runnable {

    private val downloader = PaperMcDownloader()

    // ---------------------------------------------------------- port / network --

    @Option(
        names = ["--velocity-port"],
        description = ["Port the Velocity proxy listens on (default: \${DEFAULT-VALUE})"],
        defaultValue = "25577",
    )
    var velocityPort: Int = 25577

    @Option(
        names = ["--lobby-port"],
        description = ["Port of the lobby (outpre-auth) backend server (default: \${DEFAULT-VALUE})"],
        defaultValue = "30066",
    )
    var lobbyPort: Int = 30066

    @Option(
        names = ["--game-port"],
        description = ["Port of the game (play) backend server (default: \${DEFAULT-VALUE})"],
        defaultValue = "30067",
    )
    var gamePort: Int = 30067

    @Option(
        names = ["--bind"],
        description = [
            "Velocity bind address without the port (default: \${DEFAULT-VALUE}).",
            "Use 0.0.0.0 to listen on all interfaces.",
        ],
        defaultValue = "0.0.0.0",
    )
    var bindHost: String = "0.0.0.0"

    // -------------------------------------------------------- security / plugin --

    @Option(
        names = ["--forwarding-secret"],
        description = [
            "Velocity modern forwarding shared secret.",
            "If omitted a secure random secret is generated automatically.",
        ],
    )
    var forwardingSecret: String? = null

    @Option(
        names = ["--plugin-jar"],
        description = [
            "Path to the HyperZoneLogin all-in-one jar.",
            "When provided it is copied into velocity/plugins/ automatically.",
        ],
    )
    var pluginJar: File? = null

    // ----------------------------------------------------------- file handling --

    @Option(
        names = ["--overwrite"],
        description = [
            "Overwrite existing config files AND re-download JARs that already exist.",
            "(default: \${DEFAULT-VALUE})",
        ],
        defaultValue = "false",
    )
    var overwrite: Boolean = false

    // ---------------------------------------------------------- paper download --

    @Option(
        names = ["--paper-version"],
        description = [
            "Minecraft version of the Paper server to download for lobby and game.",
            "Accepts an exact version (e.g. '1.21.4') or 'latest' (default).",
            "Run with '--list-paper-versions' to see all available versions.",
        ],
        defaultValue = "latest",
    )
    var paperVersion: String = "latest"

    @Option(
        names = ["--no-paper-download"],
        description = ["Skip downloading Paper JARs \u2014 place them in lobby/ and game/ manually."],
        defaultValue = "false",
    )
    var noPaperDownload: Boolean = false

    @Option(
        names = ["--list-paper-versions"],
        description = ["Print all available Paper versions from the PaperMC API and exit."],
        defaultValue = "false",
    )
    var listPaperVersions: Boolean = false

    // ------------------------------------------------------- velocity download --

    @Option(
        names = ["--velocity-version"],
        description = [
            "Velocity version to download.",
            "Accepts an exact version (e.g. '3.4.0-SNAPSHOT') or 'latest' (default).",
            "Run with '--list-velocity-versions' to see all available versions.",
        ],
        defaultValue = "latest",
    )
    var velocityVersion: String = "latest"

    @Option(
        names = ["--no-velocity-download"],
        description = ["Skip downloading the Velocity JAR \u2014 place it in velocity/ manually."],
        defaultValue = "false",
    )
    var noVelocityDownload: Boolean = false

    @Option(
        names = ["--list-velocity-versions"],
        description = ["Print all available Velocity versions from the PaperMC API and exit."],
        defaultValue = "false",
    )
    var listVelocityVersions: Boolean = false


    // ------------------------------------------------------------------ run --

    override fun run() {
        // ---- Version listing (early exit) ------------------------------------
        if (listPaperVersions) {
            printVersionList("paper")
            return
        }
        if (listVelocityVersions) {
            printVersionList("velocity")
            return
        }

        val baseDir = File(".").canonicalFile
        val secret = forwardingSecret ?: generateSecret()
        val selectedVelocityVersion = resolveRequestedVersion("velocity", velocityVersion, !noVelocityDownload)
        val selectedPaperVersion = resolveRequestedPaperVersion()

        println("=== HyperZoneLogin EasyDeploy ===")
        println("Deploy directory   : $baseDir")
        println("Velocity port      : $velocityPort  (bind $bindHost)")
        println("Lobby port         : $lobbyPort")
        println("Game port          : $gamePort")
        println("Overwrite          : $overwrite")
        if (!noVelocityDownload) println("Velocity version   : ${selectedVelocityVersion ?: velocityVersion}")
        println("Paper config for   : ${selectedPaperVersion ?: paperVersion}")
        if (!noPaperDownload) println("Paper version      : ${selectedPaperVersion ?: paperVersion}")
        println()

        // ---- 1. Write config files ------------------------------------------
        VelocityDeployer(
            baseDir = baseDir,
            bindHost = bindHost,
            velocityPort = velocityPort,
            lobbyPort = lobbyPort,
            gamePort = gamePort,
            forwardingSecret = secret,
            pluginJar = pluginJar,
            overwrite = overwrite,
        ).deploy()

        LobbyDeployer(
            baseDir = baseDir,
            lobbyPort = lobbyPort,
            paperVersion = selectedPaperVersion ?: paperVersion,
            forwardingSecret = secret,
            overwrite = overwrite,
        ).deploy()

        GameDeployer(
            baseDir = baseDir,
            gamePort = gamePort,
            paperVersion = selectedPaperVersion ?: paperVersion,
            forwardingSecret = secret,
            overwrite = overwrite,
        ).deploy()

        // ---- 4. Generate startup scripts ---------------------------------
        println("[Scripts] Generating startup scripts")
        ScriptGenerator(baseDir).generateAllScripts()

        // ---- 5. Summary -----------------------------------------------------
        val velocityJar: File? =
            if (noVelocityDownload) null else downloadVelocity(baseDir.resolve("velocity"), selectedVelocityVersion ?: velocityVersion)

        // ---- 3. Download Paper (once) and distribute -----------------------
        val paperJar: File? =
            if (noPaperDownload) null else downloadPaper(baseDir, selectedPaperVersion ?: paperVersion)

        // ---- 4. Summary -----------------------------------------------------
        println()
        println("=== Deployment Complete ===")
        println()

        var step = 1

        if (velocityJar == null) {
            println("  $step. Place the Velocity JAR in  velocity/  (e.g. velocity-3.4.0-SNAPSHOT-xxx.jar)")
            println("     Download: https://papermc.io/downloads/velocity")
            println()
            step++
        }
        if (paperJar == null) {
            println("  $step. Place a Paper JAR in  lobby/  and  game/  (e.g. paper-1.21.4-xxx.jar)")
            println("     Download: https://papermc.io/downloads/paper")
            println()
            step++
        }

        println("  $step. Start servers in this order:")
        println("     a) cd lobby  && ./start.sh")
        println("     b) cd game   && ./start.sh")
        println("     c) cd velocity && ./start.sh")
        println()
        println("  On Windows:")
        println("     a) cd lobby  && start.bat")
        println("     b) cd game   && start.bat")
        println("     c) cd velocity && start.bat")
        println()
        println("  Forwarding secret: $secret")
        println("  (Already written to velocity/forwarding.secret and both backend configs.)")
    }

    // ------------------------------------------------------------ helpers -----

    private fun downloadVelocity(velocityDir: File, resolvedVersion: String): File? {
        println("[Velocity] Querying PaperMC API for version list …")
        val (version, info) =
            try {
                val v = resolvedVersion
                val info = downloader.fetchLatestBuildInfo("velocity", v)
                println("[Velocity] Using $v  build ${info.buildNumber}  (channel: ${info.channel})")
                Pair(v, info)
            } catch (e: Exception) {
                System.err.println("[Velocity] ERROR: ${e.message}")
                System.err.println("[Velocity] Skipping download. Place the Velocity JAR manually.")
                return null
            }

        return try {
            println("[Velocity] Downloading ${info.fileName} …")
            val jar = downloader.downloadLatestBuild("velocity", version, velocityDir, overwrite)
            println()
            jar
        } catch (e: Exception) {
            System.err.println("[Velocity] ERROR: ${e.message}")
            System.err.println("[Velocity] Download failed. Place the Velocity JAR manually.")
            println()
            null
        }
    }

    /**
     * Downloads Paper once into lobby/ then copies the same file into game/
     * to avoid fetching the same binary twice.
     */
    private fun downloadPaper(baseDir: File, resolvedVersion: String): File? {
        println("[Paper] Querying PaperMC API for version list …")
        val (version, info) =
            try {
                val v = resolvedVersion
                val info = downloader.fetchLatestBuildInfo("paper", v)
                println("[Paper] Using $v  build ${info.buildNumber}  (channel: ${info.channel})")
                Pair(v, info)
            } catch (e: Exception) {
                System.err.println("[Paper] ERROR: ${e.message}")
                System.err.println("[Paper] Skipping download. Place Paper JARs manually.")
                return null
            }

        val lobbyDir = baseDir.resolve("lobby")
        val gameDir = baseDir.resolve("game")
        val gameJar = gameDir.resolve(info.fileName)

        return try {
            println("[Paper] Downloading ${info.fileName} for lobby/ …")
            val downloaded = downloader.downloadLatestBuild("paper", version, lobbyDir, overwrite)

            // Reuse the downloaded file for game/ (copy, not re-download)
            gameDir.mkdirs()
            if (gameJar.exists() && !overwrite) {
                println("  [exists] ${gameJar.name} in game/  (skipping — use --overwrite to replace)")
            } else {
                print("  [copy]   ${info.fileName} → game/ ")
                System.out.flush()
                downloaded.copyTo(gameJar, overwrite = true)
                println("done")
            }
            println()
            downloaded
        } catch (e: Exception) {
            System.err.println("[Paper] ERROR: ${e.message}")
            System.err.println("[Paper] Download failed. Place Paper JARs manually.")
            println()
            null
        }
    }

    private fun printVersionList(project: String) {
        println("Fetching $project versions from PaperMC API …")
        try {
            val versions = downloader.fetchVersions(project)
            println("Available $project versions (${versions.size} total):")
            println()
            versions.chunked(10).forEach { row -> println("  " + row.joinToString("  ")) }
            println()
            println("Latest: ${versions.firstOrNull() ?: "<none>"}")
        } catch (e: Exception) {
            System.err.println("ERROR: ${e.message}")
        }
    }

    private fun generateSecret(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun resolveRequestedVersion(project: String, requestedVersion: String, required: Boolean): String? =
        try {
            downloader.resolveVersion(project, requestedVersion)
        } catch (e: Exception) {
            if (required) {
                System.err.println("[${project.replaceFirstChar(Char::titlecase)}] ERROR: ${e.message}")
            }
            null
        }

    private fun resolveRequestedPaperVersion(): String? {
        if (paperVersion.equals("latest", ignoreCase = true) || !noPaperDownload) {
            return resolveRequestedVersion("paper", paperVersion, !noPaperDownload)
        }
        return paperVersion
    }
}
