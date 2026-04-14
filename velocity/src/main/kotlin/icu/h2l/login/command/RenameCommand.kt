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

import com.mojang.brigadier.arguments.StringArgumentType
import com.velocitypowered.api.command.BrigadierCommand
import com.velocitypowered.api.proxy.Player
import icu.h2l.api.command.HyperChatBrigadierRegistration
import icu.h2l.api.command.HyperChatCommandExecutor
import icu.h2l.api.command.HyperChatCommandInvocation
import icu.h2l.api.event.auth.LoginRenameEvent
import icu.h2l.api.message.HyperZoneMessagePlaceholder
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.manager.HyperZonePlayerManager
import icu.h2l.login.message.MessageKeys
import icu.h2l.login.profile.resolvePendingProfileCreateContext

class RenameCommand : HyperChatCommandExecutor {
    override fun execute(invocation: HyperChatCommandInvocation) {
        val source = invocation.source()
        val main = HyperZoneLoginMain.getInstance()
        val messages = main.messageService
        if (source !is Player) {
            messages.send(source, MessageKeys.Common.ONLY_PLAYER)
            return
        }

        val args = invocation.arguments()
        if (args.size != 1 || args[0].isBlank()) {
            messages.send(source, MessageKeys.Rename.USAGE)
            return
        }

        val hyperZonePlayer = runCatching {
            HyperZonePlayerManager.getByPlayer(source)
        }.getOrElse {
            messages.send(source, MessageKeys.Common.PLAYER_STATE_UNAVAILABLE)
            return
        }

        if (!hyperZonePlayer.isInWaitingArea()) {
            messages.send(source, MessageKeys.Rename.NOT_IN_WAITING_AREA)
            return
        }
        if (hyperZonePlayer.hasAttachedProfile()) {
            messages.send(source, MessageKeys.Rename.ALREADY_BOUND)
            return
        }

        val newRegistrationName = args[0].trim()
        if (newRegistrationName.equals(hyperZonePlayer.registrationName, ignoreCase = true)) {
            messages.send(source, MessageKeys.Rename.SAME_AS_CURRENT)
            return
        }

        val pendingContext = resolvePendingProfileCreateContext(hyperZonePlayer.getSubmittedCredentials()) ?: run {
            messages.send(source, MessageKeys.Rename.CONTEXT_CONFLICT)
            return
        }
        if (!pendingContext.hasUnboundCredentials) {
            messages.send(source, MessageKeys.Rename.CONTEXT_CONFLICT)
            return
        }
        val createBlockedReason = main.profileService.getRenameBlockedReason(
            newRegistrationName,
            pendingContext.suggestedUuid
        )
        if (createBlockedReason != null) {
            messages.send(
                source,
                MessageKeys.Rename.CREATE_BLOCKED,
                HyperZoneMessagePlaceholder.text("reason", createBlockedReason)
            )
            return
        }

        val oldRegistrationName = hyperZonePlayer.registrationName
        hyperZonePlayer.registrationName = newRegistrationName
        hyperZonePlayer.getSubmittedCredentials().forEach { credential ->
            credential.onRegistrationNameChanged(newRegistrationName)
        }

        runCatching {
            main.proxy.eventManager.fire(LoginRenameEvent(hyperZonePlayer)).join()
        }.onSuccess {
            messages.send(
                source,
                MessageKeys.Rename.REMEMBER_NAME,
                HyperZoneMessagePlaceholder.text("name", newRegistrationName)
            )

            val remainingCreateBlockedReason = main.profileService.getCreateBlockedReason(
                newRegistrationName,
                pendingContext.suggestedUuid
            )
            if (hyperZonePlayer.hasAttachedProfile()) {
                messages.send(
                    source,
                    MessageKeys.Rename.SUCCESS,
                    HyperZoneMessagePlaceholder.text("name", newRegistrationName)
                )
            } else if (remainingCreateBlockedReason != null) {
                messages.send(
                    source,
                    MessageKeys.Rename.REUUID_REQUIRED,
                    HyperZoneMessagePlaceholder.text("reason", remainingCreateBlockedReason)
                )
            } else {
                messages.send(
                    source,
                    MessageKeys.Rename.SUCCESS,
                    HyperZoneMessagePlaceholder.text("name", newRegistrationName)
                )
            }
        }.onFailure { throwable ->
            hyperZonePlayer.registrationName = oldRegistrationName
            hyperZonePlayer.getSubmittedCredentials().forEach { credential ->
                credential.onRegistrationNameChanged(oldRegistrationName)
            }
            messages.send(
                source,
                MessageKeys.Rename.EVENT_FAILED,
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
                    .then(
                        BrigadierCommand.requiredArgumentBuilder("name", StringArgumentType.word())
                            .executes { commandContext ->
                                context.execute(
                                    commandContext.source,
                                    args = arrayOf(StringArgumentType.getString(commandContext, "name"))
                                )
                            }
                    )
            }
        }
    }
}


