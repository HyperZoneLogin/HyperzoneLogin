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
 * Deploys the `lobby/` (outpre-auth) backend Minecraft server.
 *
 * The lobby server is where unauthenticated players first arrive.
 * HyperZoneLogin's Velocity plugin redirects them here until they log in,
 * then forwards them to the game server.
 *
 * Reference: https://docs.h2l.icu/manual/zh/服务器基础配置/
 */
class LobbyDeployer(
    baseDir: File,
    lobbyPort: Int,
    paperVersion: String,
    forwardingSecret: String,
    overwrite: Boolean,
) : PaperServerDeployer(
    dir = baseDir.resolve("lobby"),
    port = lobbyPort,
    paperVersion = paperVersion,
    forwardingSecret = forwardingSecret,
    label = "Lobby",
    overwrite = overwrite,
)

