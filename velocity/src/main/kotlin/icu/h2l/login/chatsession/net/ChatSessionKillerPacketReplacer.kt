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
import icu.h2l.api.log.error
import icu.h2l.api.log.warn
import icu.h2l.login.chatsession.ChatSessionUpdatePacketMatchers
import icu.h2l.login.chatsession.idtable.ChatSessionPacketIdTable
import icu.h2l.login.chatsession.packet.ChatSessionPacketView
import icu.h2l.login.manager.HyperZonePlayerManager
import icu.h2l.login.player.VelocityHyperZonePlayer
import icu.h2l.login.util.debugChatSession
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
 * ── 退役条件（三条，任一满足即自我卸载）─────────────────────────────────
 * 1. **无需拦截**：配置关闭或玩家非正版渠道，首个包即卸载；
 * 2. **命中并丢弃**：正常路径，本就只处理一次；
 * 3. **兜底预算耗尽**：见 [ChatSessionKillerLifetime]。
 *
 * 第 3 条是本 handler 能够「必有终点」的关键。`CHAT_SESSION_UPDATE` 是客户端发的，
 * 服务端不发、客户端被改动、或玩家始终停在等待区时，它可能永远不会到达；
 * 若只靠前两条，本 handler 会永远留在流水线上逐包做结构解析。
 * 因此这里无条件计数，并在超出预算时打印警告后自我卸载。
 *
 * ── 与 login success 的联动 ──────────────────────────────────────────────
 * 本 handler 挂在**后端**通道，而 `ServerLoginSuccessPacketReplacer` 挂在**客户端**通道，
 * 二者不在同一条流水线上，无法直接互相卸载。联动改走玩家对象上的标志位：
 * 客户端侧收到 login success 时置位，本 handler 读取它来决定用哪一档预算。
 * 该标志只用于**收紧**预算；即使它永远不置位，兜底预算也保证最终卸载。
 */
class ChatSessionKillerPacketReplacer(
    private val channel: Channel,
) : ChannelOutboundHandlerAdapter() {
    private lateinit var player: ConnectedPlayer
    private var hyperPlayer: VelocityHyperZonePlayer? = null
    private var shouldKill = true

    /** 兜底退役预算；见 [ChatSessionKillerLifetime]。 */
    private val lifetime = ChatSessionKillerLifetime()

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
                handleMissed(ctx, msg, promise)
                return
            }

            val hits = ChatSessionUpdatePacketMatchers.hitNames(view)
            debugChatSession {
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

    /**
     * 未命中的包：计入兜底预算，超预算则警告并卸载，否则原样放行。
     */
    private fun handleMissed(ctx: ChannelHandlerContext, msg: Any?, promise: ChannelPromise?) {
        val loginSuccessSent = hyperPlayer?.hasLoginSuccessBeenSent() == true

        if (lifetime.onForwarded(loginSuccessSent)) {
            warn {
                "[ChatSessionKiller] 长期未命中 CHAT_SESSION_UPDATE，已超出兜底预算并主动卸载：" +
                        "player=${player.username} protocol=${player.protocolVersion} " +
                        "forwarded=${lifetime.forwardedCount()} " +
                        "loginSuccessSent=$loginSuccessSent。若该玩家本应发送此包，请检查客户端或协议版本。"
            }
            retire()
        }

        super.write(ctx, msg, promise)
    }

    private fun retire() {
        runCatching { channel.pipeline().remove(this) }
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
        val resolvedHyperPlayer = HyperZonePlayerManager.getByPlayerOrNull(player)
        hyperPlayer = resolvedHyperPlayer
        shouldKill = ChatSessionPacketIdTable.isKillEnabled()
                && resolvedHyperPlayer?.isOnlinePlayer == true
        return Unit
    }
}
