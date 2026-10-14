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

package icu.h2l.login.chatsession.net

import com.velocitypowered.proxy.connection.MinecraftConnection
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection
import com.velocitypowered.proxy.connection.client.ConnectedPlayer
import icu.h2l.api.log.HyperZoneDebugType
import icu.h2l.api.log.debug
import icu.h2l.api.log.error
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.manager.HyperZonePlayerManager
import icu.h2l.login.chatsession.packet.ChatSessionPacketView
import icu.h2l.login.chatsession.ChatSessionUpdatePacketMatchers
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.util.ReferenceCountUtil

/**
 * 后端通道上拦截 `CHAT_SESSION_UPDATE` 的 outbound handler。
 *
 * 挂载于每个后端连接 initializer，因此 backend 直连模式与 outpre 桥接模式都能在
 * 该包转发给真实认证服 / 等待区后端之前将其丢弃。
 *
 * ── 识别方式 ─────────────────────────────────────────────────────────────
 * 委托给 [ChatSessionUpdatePacketMatchers]：由「版本 id 表」与「包体结构解析」两条
 * 独立通道共同判定，任一命中即丢弃。**未命中一律原样转发**，不丢弃任何无法确认的包。
 *
 * 该 handler 自身只负责取出连接上下文与玩家信息；所有结构解析都在匹配器层完成，
 * 且匹配器保证不抛异常、不推进原始缓冲区游标。
 */
class ChatSessionKillerPacketReplacer(
    private val channel: Channel,
) : ChannelOutboundHandlerAdapter() {
    private lateinit var player: ConnectedPlayer
    private var shouldKill = true

    override fun write(ctx: ChannelHandlerContext, msg: Any?, promise: ChannelPromise?) {
        try {
            initFields(ctx) ?: run {
                super.write(ctx, msg, promise)
                return
            }

            if (!shouldKill) {
                retire()
                super.write(ctx, msg, promise)
                return
            }

            // 只处理已编码的原始包；Velocity 对本包不注册类型化 packet，
            // 因此这里预期始终是 ByteBuf。
            if (msg !is ByteBuf) {
                super.write(ctx, msg, promise)
                return
            }

            val view = ChatSessionPacketView.of(msg, player.protocolVersion)
            if (view == null || !ChatSessionUpdatePacketMatchers.matches(view)) {
                super.write(ctx, msg, promise)
                return
            }

            val hits = ChatSessionUpdatePacketMatchers.hitNames(view)
            debug(HyperZoneDebugType.NETWORK_REWRITE) {
                "[ChatSessionKiller] 丢弃 CHAT_SESSION_UPDATE player=${player.username} " +
                        "protocol=${player.protocolVersion} packetId=${view.packetId} " +
                        "payloadLen=${view.payloadLength} matched=$hits"
            }

            retire()
            ReferenceCountUtil.safeRelease(msg)
            promise?.setSuccess()
        } catch (t: Throwable) {
            error(t) { "ChatSessionKillerPacketReplacer write failed: ${t.message}" }
            // 识别过程出错时保守放行，交由下游继续处理，避免误丢正常包。
            try {
                super.write(ctx, msg, promise)
            } catch (inner: Throwable) {
                try {
                    ctx.fireExceptionCaught(inner)
                } catch (_: Throwable) {
                    // ignore
                }
            }
        }
    }

    private fun retire() {
        channel.pipeline().remove(this)
    }

    private fun initFields(ctx: ChannelHandlerContext): Unit? {
        if (::player.isInitialized) {
            return Unit
        }

        val connection = ctx.channel().pipeline().get(MinecraftConnection::class.java) ?: return null
        val resolvedPlayer = when (val association = connection.association) {
            is VelocityServerConnection -> association.player
            else -> return null
        }

        player = resolvedPlayer
        val hyperPlayer = HyperZonePlayerManager.getByPlayerOrNull(player)
        shouldKill = HyperZoneLoginMain.getCoreConfig().misc.killChatSession
                && hyperPlayer?.isOnlinePlayer == true
        return Unit
    }
}
