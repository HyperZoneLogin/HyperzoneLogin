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

package icu.h2l.login.auth.floodgate.config

import icu.h2l.api.util.ConfigLoader
import org.spongepowered.configurate.ConfigurationOptions
import org.spongepowered.configurate.hocon.HoconConfigurationLoader
import org.spongepowered.configurate.objectmapping.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path

object FloodgateAuthConfigLoader {
    private const val FILE_NAME = "floodgate-auth.conf"

    fun load(dataDirectory: Path): FloodgateAuthConfig {
        return ConfigLoader.loadConfig(
            dataDirectory = dataDirectory,
            fileName = FILE_NAME,
            header = "HyperZoneLogin Floodgate Auth Configuration\nFloodgate 渠道认证与用户名修正相关配置\n",
            defaultProvider = { FloodgateAuthConfig() }
        )
    }
}
