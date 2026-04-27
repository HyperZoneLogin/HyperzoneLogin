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

package icu.h2l.login.inject.network.netty.replacer

import com.velocitypowered.api.network.ProtocolVersion
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ServerConnection
import com.velocitypowered.api.util.GameProfile
import com.velocitypowered.proxy.config.PlayerInfoForwarding
import com.velocitypowered.proxy.config.VelocityConfiguration
import com.velocitypowered.proxy.connection.MinecraftConnection
import com.velocitypowered.proxy.connection.PlayerDataForwarding
import com.velocitypowered.proxy.protocol.ProtocolUtils
import com.velocitypowered.proxy.protocol.packet.LoginPluginResponsePacket
import com.velocitypowered.proxy.protocol.packet.ServerLoginPacket
import icu.h2l.api.event.profile.LoginProfileReplaceEvent
import icu.h2l.api.log.HyperZoneDebugType
import icu.h2l.api.log.debug
import icu.h2l.api.log.error
import icu.h2l.api.log.info
import icu.h2l.api.player.HyperZonePlayer
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.manager.HyperZonePlayerManager
import icu.h2l.login.util.hasSemanticGameProfileDifference
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise

internal fun shouldRewriteLoginProfile(currentProfile: GameProfile, expectedProfile: GameProfile): Boolean {
    return hasSemanticGameProfileDifference(expectedProfile, currentProfile)
}

/**
 * 重写发往后端的登录档案包（事件驱动版）。
 *
 * 在首次 write 时触发 [LoginProfileReplaceEvent]，初始档案由
 * [resolveInitialForwardingProfile] 解析；监听器可修改事件中的档案并将 modified 置为 true。
 * - modified = false：说明无需补丁，立刻自移除；
 * - modified = true：使用事件中的档案替换登录阶段下发给后端的档案包，并输出替换成功日志。
 */
class LoginProfilePacketReplacer(
    private val channel: Channel
) : ChannelOutboundHandlerAdapter() {
    companion object {
        private const val MODERN_FORWARDING_SIGNATURE_LENGTH = 32
    }

    private lateinit var player: Player
    private lateinit var hyperPlayer: HyperZonePlayer
    private lateinit var targetServerName: String
    private lateinit var config: VelocityConfiguration

    /** 由事件监听器确认后的最终替换档案；仅在 initFields 成功（modified=true）后有效。 */
    private lateinit var replacedProfile: GameProfile

    private fun replaceMessage(msg: Any?): Any? {
        return when (msg) {
            is ServerLoginPacket -> genServerLogin()
            is LoginPluginResponsePacket -> {
                retire()
                genLoginPluginResponse(msg)
            }
            else -> msg
        }
    }

    private fun retire() {
        channel.pipeline().remove(this)
    }

    private fun isLoginServerTarget(): Boolean {
        val loginServerName = HyperZoneLoginMain.getCoreConfig().vServer.backend.fallbackAuthServer.trim()
        if (loginServerName.isBlank()) {
            return false
        }
        return targetServerName.equals(loginServerName, ignoreCase = true)
    }

    private fun genLoginPluginResponse(msg: LoginPluginResponsePacket): LoginPluginResponsePacket {
        if (config.playerInfoForwardingMode != PlayerInfoForwarding.MODERN) {
            return msg
        }

        val requestedForwardingVersion = resolveRequestedForwardingVersion(msg.content())
        val forwardingData = PlayerDataForwarding.createForwardingData(
            config.forwardingSecret,
            getPlayerRemoteAddressAsString(),
            player.protocolVersion,
            replacedProfile,
            player.identifiedKey,
            requestedForwardingVersion,
        )
        return LoginPluginResponsePacket(msg.id, true, forwardingData)
    }

    private fun resolveRequestedForwardingVersion(content: ByteBuf?): Int {
        if (content == null) {
            debug(HyperZoneDebugType.NETWORK_REWRITE) {
                "[ProfileSkinFlow] modern forwarding version missing content, fallback=${PlayerDataForwarding.MODERN_DEFAULT}, target=$targetServerName"
            }
            return PlayerDataForwarding.MODERN_DEFAULT
        }

        val readableBytes = content.readableBytes()
        if (readableBytes <= MODERN_FORWARDING_SIGNATURE_LENGTH) {
            debug(HyperZoneDebugType.NETWORK_REWRITE) {
                "[ProfileSkinFlow] modern forwarding version payload too short, fallback=${PlayerDataForwarding.MODERN_DEFAULT}, readableBytes=$readableBytes, target=$targetServerName"
            }
            return PlayerDataForwarding.MODERN_DEFAULT
        }

        val duplicate = content.duplicate()
        duplicate.skipBytes(MODERN_FORWARDING_SIGNATURE_LENGTH)
        return runCatching {
            ProtocolUtils.readVarInt(duplicate)
        }.onFailure { throwable ->
            debug(HyperZoneDebugType.NETWORK_REWRITE) {
                "[ProfileSkinFlow] modern forwarding version decode failed, fallback=${PlayerDataForwarding.MODERN_DEFAULT}, readableBytes=$readableBytes, target=$targetServerName, reason=${throwable.message}"
            }
        }.getOrDefault(PlayerDataForwarding.MODERN_DEFAULT)
    }

    private fun getPlayerRemoteAddressAsString(): String {
        val addr = player.remoteAddress.address.hostAddress
        val ipv6ScopeIdx = addr.indexOf('%')
        return if (ipv6ScopeIdx == -1) addr else addr.substring(0, ipv6ScopeIdx)
    }

    private fun genServerLogin(): ServerLoginPacket {
        return if (player.identifiedKey == null && player.protocolVersion.noLessThan(ProtocolVersion.MINECRAFT_1_19_3)) {
            ServerLoginPacket(replacedProfile.name, replacedProfile.id)
        } else {
            ServerLoginPacket(replacedProfile.name, player.identifiedKey)
        }
    }

    /** 计算本次连接应向后端转发的初始档案（供事件的 initialProfile 字段使用）。 */
    private fun resolveInitialForwardingProfile(isLoginServer: Boolean): GameProfile {
        if (isLoginServer || hyperPlayer.isInWaitingArea()) {
            return hyperPlayer.getTemporaryGameProfile()
        }
        return requireNotNull(hyperPlayer.getApplyGameProfile()) {
            "Formal profile is unavailable while resolving initial forwarding profile for clientOriginal=${hyperPlayer.clientOriginalName}"
        }
    }

    override fun write(ctx: ChannelHandlerContext, msg: Any?, promise: ChannelPromise?) {
        try {
            initFields(ctx) ?: run {
                super.write(ctx, msg, promise)
                return
            }

            val replaced = replaceMessage(msg)
            super.write(ctx, replaced, promise)
        } catch (t: Throwable) {
            error(t) { "LoginProfilePacketReplacer write failed: ${t.message}" }
            try {
                ctx.fireExceptionCaught(t)
            } catch (_: Throwable) {
                // ignore
            }
        }
    }

    private fun initFields(ctx: ChannelHandlerContext): Unit? {
        if (::player.isInitialized) {
            return Unit
        }

        val connection = ctx.channel().pipeline().get(MinecraftConnection::class.java) ?: return null
        val association = connection.association as? ServerConnection ?: return null

        player = association.player
        targetServerName = association.server.serverInfo.name
        hyperPlayer = HyperZonePlayerManager.getByPlayer(player)
        config = HyperZoneLoginMain.getInstance().proxy.configuration as VelocityConfiguration

        val isLoginServer = isLoginServerTarget()
        val initialProfile = resolveInitialForwardingProfile(isLoginServer)
        val event = LoginProfileReplaceEvent(hyperPlayer, targetServerName, isLoginServer, initialProfile)
        HyperZoneLoginMain.getInstance().proxy.eventManager.fire(event).join()

        if (!event.modified) {
            retire()
            return null
        }

        replacedProfile = event.profile
        info {
            "[LoginProfileReplace] 替换成功: server=$targetServerName name=${replacedProfile.name} uuid=${replacedProfile.id}"
        }
        return Unit
    }
}
