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

package icu.h2l.login.backend.hzlimbo.session

/**
 * 固定返回的 server list ping 数据。
 *
 * limbo 不对外暴露，因此版本名、描述、玩家槽位全部写死；仅协议号回显客户端版本，
 * 以保证客户端在服务器列表里显示为可连接。
 */
object PingData {
    private const val VERSION_NAME = "HyperZoneLogin"
    private const val DESCRIPTION = "HyperZoneLogin"

    fun json(protocol: Int): String =
        """{"version":{"name":"$VERSION_NAME","protocol":$protocol},""" +
            """"players":{"max":0,"online":0,"sample":[]},""" +
            """"description":{"text":"$DESCRIPTION"}}"""
}
