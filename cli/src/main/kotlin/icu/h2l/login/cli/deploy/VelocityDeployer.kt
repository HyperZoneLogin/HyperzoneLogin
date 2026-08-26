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

import java.io.File

/**
 * Sets up the `velocity/` sub-directory with a production-ready Velocity
 * proxy configuration pre-wired for HyperZoneLogin.
 *
 * Generated files
 * ───────────────
 *   velocity/velocity.toml              — main proxy config
 *   velocity/forwarding.secret          — modern forwarding shared secret
 *   velocity/plugins/bStats/config.txt  — bStats (disabled)
 *   velocity/plugins/hyperzonelogin/start.conf
 *   velocity/plugins/hyperzonelogin/libs/  (empty, created for the plugin)
 *   velocity/plugins/<plugin>.jar       — copied if --plugin-jar is specified
 */
class VelocityDeployer(
    private val baseDir: File,
    private val bindHost: String,
    private val velocityPort: Int,
    private val lobbyPort: Int,
    private val gamePort: Int,
    private val forwardingSecret: String,
    private val pluginJar: File?,
    overwrite: Boolean,
) : ServerDeployer(overwrite) {

    private val dir = baseDir.resolve("velocity")

    override fun deploy() {
        println("[Velocity] Deploying into ${dir.path}")
        dir.mkdirs()

        writeFile(dir.resolve("velocity.toml"), velocityToml())
        writeFile(dir.resolve("forwarding.secret"), "$forwardingSecret\n")
        writeFile(dir.resolve("plugins/hyperzonelogin/start.conf"), startConf())

        // Ensure libs directory exists for the runtime dependency loader
        dir.resolve("plugins/hyperzonelogin/libs").mkdirs()

        pluginJar?.let { jar ->
            if (!jar.exists()) {
                System.err.println("  [warn]   Plugin jar not found: ${jar.absolutePath}")
            } else {
                copyFile(jar, dir.resolve("plugins/${jar.name}"))
            }
        }

        println()
    }

    // -------------------------------------------------------- config content --

    private fun velocityToml(): String =
        """
        config-version = "2.8"
        bind = "$bindHost:$velocityPort"
        motd = "<aqua>HyperZoneLogin Server"
        show-max-players = 100
        online-mode = true
        force-key-authentication = true
        prevent-client-proxy-connections = false
        player-info-forwarding-mode = "modern"
        forwarding-secret-file = "forwarding.secret"
        announce-forge = false
        kick-existing-players = false
        ping-passthrough = "DISABLED"
        sample-players-in-ping = false
        enable-player-address-logging = true

        [packet-limiter]
        interval = 7
        packets-per-second = -1
        bytes-per-second = -1
        decompressed-bytes-per-second = 5242880

        [servers]
        lobby = "127.0.0.1:$lobbyPort"
        game = "127.0.0.1:$gamePort"
        try = [
            "lobby"
        ]

        [forced-hosts]

        [advanced]
        compression-threshold = 256
        compression-level = -1
        login-ratelimit = 0
        connection-timeout = 5000
        read-timeout = 30000
        haproxy-protocol = false
        tcp-fast-open = false
        bungee-plugin-message-channel = true
        show-ping-requests = false
        failover-on-unexpected-server-disconnect = true
        announce-proxy-commands = true
        log-command-executions = false
        log-player-connections = true
        accepts-transfers = false
        enable-reuse-port = false
        command-rate-limit = 50
        forward-commands-if-rate-limited = true
        kick-after-rate-limited-commands = 0
        tab-complete-rate-limit = 10
        kick-after-rate-limited-tab-completes = 0

        [query]
        enabled = false
        port = 25565
        map = "Velocity"
        show-plugins = false
        """.trimIndent() + "\n"

    private fun startConf(): String =
        """
        language="zh_cn"
        format=hocon
        ready=true
        """.trimIndent() + "\n"
}

