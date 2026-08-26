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
 * Deploys the `play/` (play) backend Minecraft server.
 *
 * The play server is where authenticated players are sent after a successful
 * login. It is listed as the `play` backend in velocity.toml and is the
 * primary game-play destination.
 *
 * Reference: https://docs.h2l.icu/manual/zh/服务器基础配置/
 */
class GameDeployer(
    baseDir: File,
    gamePort: Int,
    paperVersion: String,
    paperConfigMode: PaperServerDeployer.PaperConfigMode,
    forwardingSecret: String,
    overwrite: Boolean,
) : PaperServerDeployer(
    dir = baseDir.resolve("play"),
    port = gamePort,
    paperVersion = paperVersion,
    paperConfigMode = paperConfigMode,
    forwardingSecret = forwardingSecret,
    label = "play",
    overwrite = overwrite,
)

