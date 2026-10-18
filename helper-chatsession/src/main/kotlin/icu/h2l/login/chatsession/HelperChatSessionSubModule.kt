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

package icu.h2l.login.chatsession

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import icu.h2l.api.HyperZoneApi
import icu.h2l.api.log.info
import icu.h2l.api.message.HyperZoneModuleMessageResources
import icu.h2l.api.module.HyperSubModule
import icu.h2l.login.chatsession.idtable.ChatSessionPacketIdTable
import icu.h2l.login.chatsession.net.ChatSessionKillerPacketReplacer

/**
 * `CHAT_SESSION_UPDATE` 拦截助手（helper-chatsession）模块接入点。
 *
 * 通过 [HyperZoneApi.net] 暴露的后端 channel handler 注册器，把拦截 handler 挂到
 * 每条新建后端连接上。开关与 id 表都读本模块自己的 `helper-chatsession.conf`。
 */
class HelperChatSessionSubModule : HyperSubModule {

    override fun register(api: HyperZoneApi) {
        val proxy = api.proxy
        val dataDirectory = api.dataDirectory

        HyperZoneModuleMessageResources.copyBundledLocales(dataDirectory, "helper-chatsession", javaClass.classLoader)
        ChatSessionPacketIdTable.load(dataDirectory)

        api.net.backendChannelHandlers.register("h2l_chat_session_killer") { channel ->
            ChatSessionKillerPacketReplacer(channel, api.hyperZonePlayers)
        }

        // 卸载前把去抖窗口内可能残留的 id 学习结果落盘。
        proxy.eventManager.register(api, object {
            @Subscribe
            fun onShutdown(@Suppress("UNUSED_PARAMETER") event: ProxyShutdownEvent) {
                ChatSessionPacketIdTable.flushNow()
            }
        })

        info { "HelperChatSessionSubModule 已加载" }
    }
}
