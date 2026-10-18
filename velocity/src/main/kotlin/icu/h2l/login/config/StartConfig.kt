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

package icu.h2l.login.config

/**
 * start.conf — HyperZoneLogin 启动前置配置。
 *
 * 此配置不参与 i18n 翻译，不走 ConfigLoader，由 StartConfigLoader 直接读写。
 * 它是第一个被加载的配置文件，用于确定后续所有配置的语言。
 *
 * @property language 配置注释语言（如 zh_cn / en_us）。对应 config-comments 资源目录中的 locale key。
 * @property ready    就绪标志。必须设为 true 后插件才会正常启动。
 *                    首次生成文件时默认为 false，提示管理员先完成配置再启动。
 */
data class StartConfig(
    val language: String = "zh_cn",
    val ready: Boolean = false
)

