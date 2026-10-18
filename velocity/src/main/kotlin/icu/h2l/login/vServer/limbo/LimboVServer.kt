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

package icu.h2l.login.vServer.limbo

import com.velocitypowered.api.proxy.server.ServerInfo
import icu.h2l.login.HyperZoneLoginMain
import java.net.InetSocketAddress

/**
 * limbo 虚拟等待服的识别工具。
 *
 * 当「下层认证等待服是 limbo 虚拟服（[icu.h2l.login.config.VServerConfig.LimboConfig.enabled]）」
 * 且「当前后端连接的目标正是登录服」时，网络注入层无需再做 chatSession 拦截、档案替换等
 * 针对真实后端等待服的补偿操作，可直接让对应 handler 退役以减少计算。
 */
object LimboVServer {

    /**
     * 判断指定后端 [ServerInfo] 是否属于「limbo 登录服」。
     *
     * - 名称优先：与当前模式下登录服的逻辑名比对（outpre 用 auth 逻辑名，backend 用 fallbackAuthServer）；
     * - 地址兜底：与 backend-hzlimbo 模块动态回填的 limbo 实际监听地址比对。
     */
    fun isLimboLoginServer(serverInfo: ServerInfo?): Boolean {
        if (serverInfo == null) return false
        val vServer = HyperZoneLoginMain.getCoreConfig().vServer
        val limbo = vServer.limbo
        if (!limbo.enabled) return false

        // 登入服务器逻辑名（按模式）
        val loginName = if (vServer.mode.trim().lowercase() == "outpre") {
            vServer.outpre.outpreAuthTargetLabel().trim()
        } else {
            vServer.backend.fallbackAuthServer.trim()
        }
        if (loginName.isNotBlank() && loginName.equals(serverInfo.name, ignoreCase = true)) {
            return true
        }

        // 地址兜底：与 limbo 模块动态回填的实际监听地址一致
        val address = serverInfo.address ?: return false
        val host = limbo.host.trim()
        if (host.isBlank() || limbo.port !in 1..65535 || address.port != limbo.port) {
            return false
        }
        val addrHost = address.hostString
        val addrIp = address.address?.hostAddress
        return addrHost.equals(host, ignoreCase = true) ||
            (addrIp != null && addrIp.equals(host, ignoreCase = true))
    }
}
