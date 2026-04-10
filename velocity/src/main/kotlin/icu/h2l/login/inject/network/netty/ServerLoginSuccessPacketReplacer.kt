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

package icu.h2l.login.inject.network.netty

import com.velocitypowered.proxy.protocol.packet.ServerLoginSuccessPacket
import icu.h2l.api.log.debug
import icu.h2l.api.log.error
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.manager.HyperZonePlayerManager
import icu.h2l.login.player.ProfileSkinApplySupport
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise

class ServerLoginSuccessPacketReplacer(
    private val channel: Channel
) : ChannelOutboundHandlerAdapter() {

    override fun write(ctx: ChannelHandlerContext, msg: Any?, promise: ChannelPromise?) {
        try {
            if (!HyperZoneLoginMain.getMiscConfig().enableReplaceGameProfile || msg !is ServerLoginSuccessPacket) {
                super.write(ctx, msg, promise)
                return
            }

            val hyperPlayer = runCatching {
                HyperZonePlayerManager.getByChannel(channel)
            }.getOrNull()

            if (hyperPlayer == null) {
                debug {
                    "[ProfileSkinFlow] login success passthrough: no hyper player for channel=$channel"
                }
                super.write(ctx, msg, promise)
                return
            }

            val mergedProfile = ProfileSkinApplySupport.apply(hyperPlayer)
            if (mergedProfile == null) {
                ctx.pipeline().remove(this)
                super.write(ctx, msg, promise)
                return
            }
            msg.uuid = mergedProfile.id
            msg.username = mergedProfile.name
            msg.properties = mergedProfile.properties

            ctx.pipeline().remove(this)
            super.write(ctx, msg, promise)
        } catch (throwable: Throwable) {
            error(throwable) { "ServerLoginSuccessPacketReplacer write failed: ${throwable.message}" }
            try {
                ctx.fireExceptionCaught(throwable)
            } catch (_: Throwable) {
                // ignore
            }
        }
    }


}

