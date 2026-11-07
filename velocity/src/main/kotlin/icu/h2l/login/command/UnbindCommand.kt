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
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.manager.HyperZonePlayerManager
import icu.h2l.login.message.MessageKeys
import icu.h2l.login.profile.UserUnbindService

/**
 * `/unbind`：删除当前登录渠道的绑定并踢出玩家。
 *
 * 该命令只允许做两件事：
 *
 * 1. 调用 [UserUnbindService.unbind] 删除当前渠道账号绑定；
 * 2. 成功后玩家已被断开，不再发送任何后续指令或提示。
 *
 * 禁止在该命令中补充“重新建档”“自动绑定”“切换档案”等操作。
 * 解绑与重新绑定必须是两个分离的会话步骤，否则会造成 User/InGameProfile 冲突。
 */
class UnbindCommand(
    private val unbindService: UserUnbindService,
) : HyperChatCommandExecutor {
    override fun execute(invocation: HyperChatCommandInvocation) {
        val messages = HyperZoneLoginMain.getInstance().messageService
        val source = invocation.source()
        if (source !is Player) {
            messages.send(source, MessageKeys.Common.ONLY_PLAYER)
            return
        }

        val hyperPlayer = runCatching {
            HyperZonePlayerManager.getByPlayer(source)
        }.getOrElse {
            messages.send(source, MessageKeys.Common.PLAYER_STATE_UNAVAILABLE)
            return
        }

        val result = unbindService.unbind(hyperPlayer)
        if (!result.success) {
            source.sendMessage(result.message)
        }
    }
}
