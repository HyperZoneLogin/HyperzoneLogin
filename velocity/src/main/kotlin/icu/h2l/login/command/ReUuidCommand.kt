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
import icu.h2l.api.command.HyperChatBrigadierRegistration
import icu.h2l.api.command.HyperChatCommandExecutor
import icu.h2l.api.command.HyperChatCommandInvocation
import icu.h2l.api.event.auth.LoginReUuidEvent
import icu.h2l.api.message.HyperZoneMessagePlaceholder
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.manager.HyperZonePlayerManager
import icu.h2l.login.message.MessageKeys
import icu.h2l.login.profile.resolvePendingProfileCreateContext

class ReUuidCommand : HyperChatCommandExecutor {
    override fun execute(invocation: HyperChatCommandInvocation) {
        val source = invocation.source()
        val main = HyperZoneLoginMain.getInstance()
        val messages = main.messageService
        if (source !is Player) {
            messages.send(source, MessageKeys.Common.ONLY_PLAYER)
            return
        }

        val args = invocation.arguments()
        if (args.isNotEmpty()) {
            messages.send(source, MessageKeys.ReUuid.USAGE)
            return
        }

        val hyperZonePlayer = runCatching {
            HyperZonePlayerManager.getByPlayer(source)
        }.getOrElse {
            messages.send(source, MessageKeys.Common.PLAYER_STATE_UNAVAILABLE)
            return
        }

        if (!hyperZonePlayer.isInWaitingArea()) {
            messages.send(source, MessageKeys.ReUuid.NOT_IN_WAITING_AREA)
            return
        }
        if (hyperZonePlayer.hasAttachedProfile()) {
            messages.send(source, MessageKeys.ReUuid.ALREADY_BOUND)
            return
        }

        val pendingContext = resolvePendingProfileCreateContext(hyperZonePlayer.getSubmittedCredentials()) ?: run {
            messages.send(source, MessageKeys.ReUuid.CONTEXT_CONFLICT)
            return
        }
        if (!pendingContext.hasUnboundCredentials) {
            messages.send(source, MessageKeys.ReUuid.CONTEXT_CONFLICT)
            return
        }

        val createBlockedReason = main.profileService.getReUuidBlockedReason(hyperZonePlayer.registrationName)
        if (createBlockedReason != null) {
            messages.send(
                source,
                MessageKeys.ReUuid.CREATE_BLOCKED,
                HyperZoneMessagePlaceholder.text("reason", createBlockedReason)
            )
            return
        }

        runCatching {
            main.proxy.eventManager.fire(LoginReUuidEvent(hyperZonePlayer)).join()
        }.onSuccess {
            messages.send(source, MessageKeys.ReUuid.SUCCESS)
        }.onFailure { throwable ->
            messages.send(
                source,
                MessageKeys.ReUuid.EVENT_FAILED,
                HyperZoneMessagePlaceholder.text(
                    "reason",
                    throwable.message?.takeUnless { it.isBlank() } ?: throwable.javaClass.simpleName
                )
            )
        }
    }

    companion object {
        fun brigadier(): HyperChatBrigadierRegistration {
            return HyperChatBrigadierRegistration { context ->
                context.literal()
                    .executes { commandContext ->
                        context.execute(commandContext.source)
                    }
            }
        }
    }
}


