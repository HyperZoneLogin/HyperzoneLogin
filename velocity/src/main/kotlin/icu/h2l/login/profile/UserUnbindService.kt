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

package icu.h2l.login.profile

import icu.h2l.api.player.HyperZonePlayer
import icu.h2l.api.profile.HyperZoneUserService
import icu.h2l.login.message.MessageKeys
import icu.h2l.login.message.MessageService
import net.kyori.adventure.text.Component

/**
 * 当前登录渠道的解绑服务。
 *
 * ============================================================================
 * ⚠ unbind 的安全边界（强制，后续修改不得突破）
 * ============================================================================
 *
 * unbind 的唯一职责是：
 *
 * 1. 通过当前登录凭证删除“当前渠道账号 -> 当前 User”的绑定数据；
 * 2. 成功删除后立即踢出玩家。
 *
 * 除上述两步外，禁止在本服务或凭证的 `unbind(...)` 实现中执行任何额外操作，
 * 尤其禁止：
 *
 * - 创建新的 HZL User；
 * - 合并 HZL User；
 * - 创建、删除、切换或修改 InGameProfile；
 * - 删除当前 User；
 * - 自动重新绑定凭证；
 * - 解绑后继续走登录、建档、attach 或补全身份流程；
 * - 修改其他渠道或其他账号的绑定。
 *
 * 原因：unbind 的语义是“把这个渠道账号从这个 User 上摘掉，然后立刻终止当前会话”。
 * 如果在删除绑定后又顺手建档、重新绑定或调整 InGameProfile，渠道账号会在同一个
 * 登录链路里被重新关联，立即产生新的 User/InGameProfile 冲突，甚至让玩家以为
 * 自己解绑成功但实际又绑回了错误档案。
 *
 * 需要更换档案时，正确流程是：
 *
 * 1. 先 unbind 并让玩家重新登录；
 * 2. 再通过 `/bindcode use` 显式绑定或合并到目标档案。
 *
 * 不允许把这两个阶段塞进一次 unbind 调用里。
 */
class UserUnbindService(
    private val userService: HyperZoneUserService,
    private val messageService: MessageService,
) {
    private val logger = java.util.logging.Logger.getLogger("hzl-unbind")

    data class Result(
        val success: Boolean,
        val message: Component,
    )

    /**
     * 删除当前登录渠道的绑定并踢出玩家。
     *
     * 该方法只允许调用一次 `credential.unbind(user.id)`，成功后再调用
     * `disconnect(...)`。不要在中间插入任何 User / InGameProfile 写入逻辑。
     */
    fun unbind(player: HyperZonePlayer): Result {
        val messages = messageService

        val user = userService.getAttachedUser(player)
            ?: return Result(
                false,
                messages.render(player, MessageKeys.Unbind.NOT_ATTACHED),
            )

        val credential = player.getSubmittedCredentials().singleOrNull()
            ?: return Result(
                false,
                messages.render(player, MessageKeys.Unbind.NO_CREDENTIAL),
            )

        val removed = try {
            credential.unbind(user.id)
        } catch (unsupported: UnsupportedOperationException) {
            return Result(
                false,
                messages.render(player, MessageKeys.Unbind.UNSUPPORTED),
            )
        } catch (throwable: Throwable) {
            logger.warning(
                "解绑当前渠道失败: player=${player.logLabel} channel=${credential.channelId} " +
                    "user=${user.id} reason=${throwable.message}",
            )
            return Result(
                false,
                messages.render(player, MessageKeys.Unbind.FAILED),
            )
        }

        if (!removed) {
            return Result(
                false,
                messages.render(player, MessageKeys.Unbind.FAILED),
            )
        }

        val disconnectMessage = messages.render(player, MessageKeys.Unbind.DISCONNECTED)
        val proxyPlayer = player.getProxyPlayerOrNull()
            ?: return Result(
                false,
                messages.render(player, MessageKeys.Unbind.FAILED),
            )

        proxyPlayer.disconnect(disconnectMessage)
        return Result(true, disconnectMessage)
    }
}
