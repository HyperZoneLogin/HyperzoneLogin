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

package icu.h2l.login.vServer.limbo.command

import com.velocitypowered.api.proxy.Player
import icu.h2l.api.command.HyperChatCommandExecutor
import icu.h2l.api.command.HyperChatCommandInvocation
import icu.h2l.login.manager.HyperZonePlayerManager
import icu.h2l.login.player.VelocityHyperZonePlayer
import net.kyori.adventure.text.Component

class ExitLimboCommand : HyperChatCommandExecutor {
    override fun execute(invocation: HyperChatCommandInvocation) {
        val source = invocation.source()
        if (source !is Player) {
            source.sendPlainMessage("§c该命令只能由玩家执行")
            return
        }

        val hyperZonePlayer = HyperZonePlayerManager.getByPlayer(source)
        if (hyperZonePlayer.isInWaitingArea()) {
            source.sendPlainMessage("§c你仍处于等待区：尚未完成认证或尚未绑定游戏档案")
            return
        }

        (hyperZonePlayer as VelocityHyperZonePlayer).exitLimbo()
        source.sendMessage(Component.text("§a已尝试退出认证服务器"))
    }

    override fun hasPermission(invocation: HyperChatCommandInvocation): Boolean {
        return true
    }
}