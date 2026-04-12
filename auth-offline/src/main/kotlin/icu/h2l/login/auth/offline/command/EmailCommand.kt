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

package icu.h2l.login.auth.offline.command

import com.velocitypowered.api.proxy.Player
import icu.h2l.api.command.HyperChatCommandInvocation
import icu.h2l.login.auth.offline.OfflineAuthMessages
import icu.h2l.login.auth.offline.service.OfflineAuthService

class EmailCommand(
    private val authService: OfflineAuthService
) : BasePlaceholderAuthCommand({ OfflineAuthMessages.EMAIL_USAGE }) {
    override fun execute(invocation: HyperChatCommandInvocation) {
        val source = invocation.source()
        if (source !is Player) {
            source.sendMessage(OfflineAuthMessages.ONLY_PLAYER)
            return
        }

        val args = invocation.arguments()
        if (args.isEmpty()) {
            source.sendMessage(OfflineAuthMessages.EMAIL_USAGE)
            return
        }

        val result = when (args[0].lowercase()) {
            "add" -> {
                if (args.size != 4) {
                    source.sendMessage(OfflineAuthMessages.EMAIL_ADD_USAGE)
                    return
                }
                authService.addEmail(source, args[1], args[2], args[3])
            }

            "change" -> {
                if (args.size != 4) {
                    source.sendMessage(OfflineAuthMessages.EMAIL_CHANGE_USAGE)
                    return
                }
                authService.changeEmail(source, args[1], args[2], args[3])
            }

            "show" -> {
                if (args.size != 2) {
                    source.sendMessage(OfflineAuthMessages.EMAIL_SHOW_USAGE)
                    return
                }
                authService.showEmail(source, args[1])
            }

            "recovery" -> {
                if (args.size != 2) {
                    source.sendMessage(OfflineAuthMessages.EMAIL_RECOVERY_USAGE)
                    return
                }
                authService.startEmailRecovery(source, args[1])
            }

            "code" -> {
                if (args.size != 2) {
                    source.sendMessage(OfflineAuthMessages.EMAIL_CODE_USAGE)
                    return
                }
                authService.verifyRecoveryCode(source, args[1])
            }

            "setpassword" -> {
                if (args.size != 3) {
                    source.sendMessage(OfflineAuthMessages.EMAIL_SETPASSWORD_USAGE)
                    return
                }
                if (args[1] != args[2]) {
                    source.sendMessage(OfflineAuthMessages.PASSWORD_MISMATCH)
                    return
                }
                authService.setPasswordByRecovery(source, args[1])
            }

            else -> {
                source.sendMessage(OfflineAuthMessages.EMAIL_USAGE)
                return
            }
        }

        source.sendMessage(result.message)
    }
}

