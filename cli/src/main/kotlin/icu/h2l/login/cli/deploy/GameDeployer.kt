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
 * Deploys the `game/` (play) backend Minecraft server.
 *
 * The game server is where authenticated players are sent after a successful
 * login. It is listed as the `game` backend in velocity.toml and is the
 * primary game-play destination.
 *
 * Reference: https://docs.h2l.icu/manual/zh/服务器基础配置/
 */
class GameDeployer(
    baseDir: File,
    gamePort: Int,
    paperVersion: String,
    forwardingSecret: String,
    overwrite: Boolean,
) : PaperServerDeployer(
    dir = baseDir.resolve("game"),
    port = gamePort,
    paperVersion = paperVersion,
    forwardingSecret = forwardingSecret,
    label = "Game",
    overwrite = overwrite,
)

