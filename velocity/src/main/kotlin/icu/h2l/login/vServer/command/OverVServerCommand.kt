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

package icu.h2l.login.vServer.command

import com.velocitypowered.api.proxy.Player
import icu.h2l.api.command.HyperChatCommandExecutor
import icu.h2l.api.command.HyperChatCommandInvocation
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.manager.HyperZonePlayerManager
import icu.h2l.login.message.MessageKeys

class OverVServerCommand : HyperChatCommandExecutor {
    override fun execute(invocation: HyperChatCommandInvocation) {
        val messages = HyperZoneLoginMain.getInstance().messageService
        val source = invocation.source()
        if (source !is Player) {
            messages.send(source, MessageKeys.Common.ONLY_PLAYER)
            return
        }

        if (!HyperZoneLoginMain.getCoreConfig().debug.slowTest.enabled) {
            messages.send(source, MessageKeys.Over.DISABLED)
            return
        }

        if (invocation.arguments().isNotEmpty()) {
            messages.send(source, MessageKeys.Over.USAGE)
            return
        }

        val hyperZonePlayer = runCatching {
            HyperZonePlayerManager.getByPlayer(source)
        }.getOrElse {
            messages.send(source, MessageKeys.Common.PLAYER_STATE_UNAVAILABLE)
            return
        }

        if (hyperZonePlayer.hasAttachedProfile()) {
            messages.send(source, MessageKeys.Over.NOT_IN_WAITING_AREA)
            return
        }

        runCatching {
            HyperZoneLoginMain.getInstance().loginManager.overVerify(hyperZonePlayer)
        }.onFailure { throwable ->
            HyperZoneLoginMain.getInstance().logger.warn(
                "玩家 ${hyperZonePlayer.clientOriginalName} 通过 /over 完成慢测试验证失败: ${throwable.message}",
                throwable
            )
            messages.send(source, MessageKeys.Over.FAILED)
        }
    }

    override fun hasPermission(invocation: HyperChatCommandInvocation): Boolean {
        return true
    }
}

