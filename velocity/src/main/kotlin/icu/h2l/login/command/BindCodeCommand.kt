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

package icu.h2l.login.command

import com.velocitypowered.api.proxy.Player
import icu.h2l.api.command.HyperChatCommandExecutor
import icu.h2l.api.command.HyperChatCommandInvocation
import icu.h2l.login.manager.HyperZonePlayerManager
import icu.h2l.login.profile.ProfileBindingCodeService

class BindCodeCommand(
    private val bindingCodeService: ProfileBindingCodeService
) : HyperChatCommandExecutor {
    override fun execute(invocation: HyperChatCommandInvocation) {
        val source = invocation.source()
        if (source !is Player) {
            source.sendPlainMessage("§c该命令只能由玩家执行")
            return
        }

        val hyperPlayer = runCatching {
            HyperZonePlayerManager.getByPlayer(source)
        }.getOrElse {
            source.sendPlainMessage("§c当前无法获取登录态玩家对象")
            return
        }

        val args = invocation.arguments()
        if (args.isEmpty()) {
            source.sendPlainMessage("§e用法: /bindcode generate 或 /bindcode use <绑定码>")
            return
        }

        val result = when (args[0].lowercase()) {
            "generate", "gen", "create" -> bindingCodeService.generate(hyperPlayer)
            "use" -> {
                if (args.size < 2) {
                    source.sendPlainMessage("§e用法: /bindcode use <绑定码>")
                    return
                }
                bindingCodeService.use(hyperPlayer, args[1])
            }
            else -> {
                source.sendPlainMessage("§e用法: /bindcode generate 或 /bindcode use <绑定码>")
                return
            }
        }

        source.sendMessage(result.message)
    }
}

