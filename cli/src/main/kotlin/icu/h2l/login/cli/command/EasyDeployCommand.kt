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
import icu.h2l.login.cli.deploy.PaperDownloader
import icu.h2l.login.cli.deploy.VelocityDeployer
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
        "  velocity/  — Velocity proxy (public entry point)",
        "  lobby/     — Lobby / pre-auth Paper server (outpre-auth backend)",
        "  game/      — Game Paper server (play backend)",
        "",
        "Each directory contains all configuration files needed to start the server.",
        "Velocity uses modern forwarding; the generated secret is shared to both backends.",
    ],
)
class EasyDeployCommand : Runnable {

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
            "Absolute or relative path to the HyperZoneLogin all-in-one jar.",
            "When provided it is copied into velocity/plugins/ automatically.",
        ],
    )
    var pluginJar: File? = null

    @Option(
        names = ["--overwrite"],
        description = ["Overwrite existing config files (default: \${DEFAULT-VALUE})"],
        defaultValue = "false",
    )
    var overwrite: Boolean = false

    @Option(
        names = ["--paper-version"],
        description = [
            "Paper server version to download for the lobby and game backends.",
            "Use 'latest' (default) to automatically pick the newest stable release,",
            "or specify an exact version like '1.21.4'.",
            "Pass '--no-paper-download' to skip downloading altogether.",
        ],
        defaultValue = "latest",
    )
    var paperVersion: String = "latest"

    @Option(
        names = ["--no-paper-download"],
        description = ["Skip downloading the Paper jar (you will place it manually)."],
        defaultValue = "false",
    )
    var noPaperDownload: Boolean = false

    // ------------------------------------------------------------------ run --

    override fun run() {
        val baseDir = File(".").canonicalFile
        val secret = forwardingSecret ?: generateSecret()

        println("=== HyperZoneLogin EasyDeploy ===")
        println("Deploy directory : $baseDir")
        println("Velocity port    : $velocityPort  (bind $bindHost)")
        println("Lobby port       : $lobbyPort")
        println("Game port        : $gamePort")
        println("Overwrite config : $overwrite")
        if (!noPaperDownload) println("Paper version    : $paperVersion")
        println()

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
            forwardingSecret = secret,
            overwrite = overwrite,
        ).deploy()

        GameDeployer(
            baseDir = baseDir,
            gamePort = gamePort,
            forwardingSecret = secret,
            overwrite = overwrite,
        ).deploy()

        // ---- Paper download ---------------------------------------------------
        if (!noPaperDownload) {
            println("[Paper] Resolving version from https://api.papermc.io ...")
            val resolvedVersion =
                try {
                    PaperDownloader.resolveVersion(paperVersion)
                } catch (e: Exception) {
                    System.err.println("[Paper] ERROR: ${e.message}")
                    System.err.println("[Paper] Skipping download. Place the Paper jar manually.")
                    null
                }

            if (resolvedVersion != null) {
                println("[Paper] Using version $resolvedVersion")
                val lobbyDir = baseDir.resolve("lobby")
                val gameDir = baseDir.resolve("game")
                try {
                    println("[Paper] Downloading for lobby/")
                    PaperDownloader.downloadLatestBuild(resolvedVersion, lobbyDir)
                    println("[Paper] Downloading for game/")
                    PaperDownloader.downloadLatestBuild(resolvedVersion, gameDir)
                } catch (e: Exception) {
                    System.err.println("[Paper] ERROR: ${e.message}")
                    System.err.println("[Paper] Download failed. Place the Paper jar manually.")
                }
            }
            println()
        }

        println()
        println("=== Deployment Complete ===")
        println()
        println("Next steps:")
        println("  1. Place the Velocity jar in  velocity/  (e.g. velocity-3.4.0-SNAPSHOT.jar)")
        println("     Download: https://papermc.io/downloads/velocity")
        println()
        if (noPaperDownload) {
            println("  2. Place the Paper jar in  lobby/  and  game/  (e.g. paper-1.21.4.jar)")
            println("     Download: https://papermc.io/downloads/paper")
            println()
        }
        println("  ${if (noPaperDownload) "3" else "2"}. Start servers in this order:")
        println("     a) lobby  → java -jar paper-*.jar  (in lobby/)")
        println("     b) game   → java -jar paper-*.jar  (in game/)")
        println("     c) proxy  → java -jar velocity-*.jar  (in velocity/)")
        println()
        println("  Forwarding secret: $secret")
        println("  (The same secret is already written to velocity/forwarding.secret")
        println("   and both backend server configs.)")
    }

    private fun generateSecret(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

