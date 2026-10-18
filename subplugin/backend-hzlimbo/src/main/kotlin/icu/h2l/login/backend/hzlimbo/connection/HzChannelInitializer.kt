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

package icu.h2l.login.backend.hzlimbo.connection

import com.velocitypowered.proxy.VelocityServer
import com.velocitypowered.proxy.connection.MinecraftConnection
import com.velocitypowered.proxy.protocol.ProtocolUtils
import com.velocitypowered.proxy.protocol.StateRegistry
import com.velocitypowered.proxy.protocol.netty.MinecraftDecoder
import com.velocitypowered.proxy.protocol.netty.MinecraftEncoder
import com.velocitypowered.proxy.protocol.netty.MinecraftVarintFrameDecoder
import com.velocitypowered.proxy.protocol.netty.MinecraftVarintLengthEncoder
import icu.h2l.login.backend.hzlimbo.HzLimboServer
import icu.h2l.login.backend.hzlimbo.session.HandshakeHandler
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.handler.timeout.ReadTimeoutHandler
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 为每条接入连接装配与 Velocity 客户端连接一致的 Netty 管线（帧/编解码），
 * 并以 [MinecraftConnection] 作为末端 handler 路由包到会话状态机。
 *
 * 与 Velocity `ServerChannelInitializer` 的差异：limbo 仅支持 1.20.2+，无需 legacy ping、
 * HAProxy 与包速率限制，也不启用压缩。
 */
class HzChannelInitializer(
    private val server: HzLimboServer,
    private val velocityServer: VelocityServer,
) : ChannelInitializer<Channel>() {

    override fun initChannel(ch: Channel) {
        ch.pipeline()
            .addLast("frame-decoder", MinecraftVarintFrameDecoder(ProtocolUtils.Direction.SERVERBOUND))
            .addLast("read-timeout", ReadTimeoutHandler(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            .addLast("frame-encoder", MinecraftVarintLengthEncoder.INSTANCE)
            .addLast("minecraft-decoder", MinecraftDecoder(ProtocolUtils.Direction.SERVERBOUND))
            .addLast("minecraft-encoder", MinecraftEncoder(ProtocolUtils.Direction.CLIENTBOUND))

        val connection = MinecraftConnection(ch, velocityServer, UUID.randomUUID())
        connection.setActiveSessionHandler(StateRegistry.HANDSHAKE, HandshakeHandler(connection, server))
        ch.pipeline().addLast("handler", connection)
    }

    companion object {
        private const val READ_TIMEOUT_SECONDS = 30L
    }
}
