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

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.velocitypowered.api.command.BrigadierCommand
import com.velocitypowered.api.command.CommandSource
import icu.h2l.api.command.HyperChatBrigadierContext
import icu.h2l.api.command.HyperChatBrigadierRegistration

object OfflineAuthBrigadierCommands {
    fun login(): HyperChatBrigadierRegistration {
        return HyperChatBrigadierRegistration { context ->
            context.literal()
                .executes { commandContext ->
                    context.execute(commandContext.source)
                }
                .then(loginPassword(context))
                .then(loginAs(context))
        }
    }

    private fun loginPassword(context: HyperChatBrigadierContext) =
        word("password")
            .executes { commandContext ->
                context.execute(
                    commandContext.source,
                    args = arrayOf(
                        StringArgumentType.getString(commandContext, "password")
                    )
                )
            }
            .then(
                word("code")
                    .executes { commandContext ->
                        context.execute(
                            commandContext.source,
                            args = arrayOf(
                                StringArgumentType.getString(commandContext, "password"),
                                StringArgumentType.getString(commandContext, "code")
                            )
                        )
                    }
            )

    private fun loginAs(context: HyperChatBrigadierContext) =
        BrigadierCommand.literalArgumentBuilder("as")
            .then(
                word("username")
                    .then(
                        word("password")
                            .executes { commandContext ->
                                context.execute(
                                    commandContext.source,
                                    args = arrayOf(
                                        "as",
                                        StringArgumentType.getString(commandContext, "username"),
                                        StringArgumentType.getString(commandContext, "password")
                                    )
                                )
                            }
                            .then(
                                word("code")
                                    .executes { commandContext ->
                                        context.execute(
                                            commandContext.source,
                                            args = arrayOf(
                                                "as",
                                                StringArgumentType.getString(commandContext, "username"),
                                                StringArgumentType.getString(commandContext, "password"),
                                                StringArgumentType.getString(commandContext, "code")
                                            )
                                        )
                                    }
                            )
                    )
            )

    fun register(): HyperChatBrigadierRegistration {
        return doublePasswordCommand("register", "password", "confirmPassword")
    }

    fun changePassword(): HyperChatBrigadierRegistration {
        return HyperChatBrigadierRegistration { context ->
            context.literal()
                .executes { commandContext ->
                    context.execute(commandContext.source)
                }
                .then(
                    word("oldPassword")
                        .then(
                            word("newPassword")
                                .executes { commandContext ->
                                    context.execute(
                                        commandContext.source,
                                        args = arrayOf(
                                            StringArgumentType.getString(commandContext, "oldPassword"),
                                            StringArgumentType.getString(commandContext, "newPassword")
                                        )
                                    )
                                }
                        )
                )
        }
    }

    fun logout(): HyperChatBrigadierRegistration {
        return plainCommand("logout")
    }

    fun unregister(): HyperChatBrigadierRegistration {
        return HyperChatBrigadierRegistration { context ->
            context.literal()
                .executes { commandContext ->
                    context.execute(commandContext.source)
                }
                .then(
                    word("password")
                        .executes { commandContext ->
                            context.execute(
                                commandContext.source,
                                args = arrayOf(
                                    StringArgumentType.getString(commandContext, "password")
                                )
                            )
                        }
                )
        }
    }

    fun email(): HyperChatBrigadierRegistration {
        return HyperChatBrigadierRegistration { context ->
            context.literal()
                .executes { commandContext ->
                    context.execute(commandContext.source)
                }
                .then(emailAdd(context))
                .then(emailChange(context))
                .then(emailShow(context))
                .then(emailRecovery(context))
                .then(emailCode(context))
                .then(emailSetPassword(context))
        }
    }

    fun totp(): HyperChatBrigadierRegistration {
        return HyperChatBrigadierRegistration { context ->
            context.literal()
                .executes { commandContext ->
                    context.execute(commandContext.source)
                }
                .then(totpAdd(context, "add"))
                .then(totpAdd(context, "enable"))
                .then(totpConfirm(context))
                .then(totpRemove(context, "remove"))
                .then(totpRemove(context, "disable"))
        }
    }

    private fun emailAdd(context: HyperChatBrigadierContext) =
        BrigadierCommand.literalArgumentBuilder("add")
            .then(
                word("currentPassword")
                    .then(
                        word("email")
                            .then(
                                word("confirmEmail")
                                    .executes { commandContext ->
                                        context.execute(
                                            commandContext.source,
                                            args = arrayOf(
                                                "add",
                                                StringArgumentType.getString(commandContext, "currentPassword"),
                                                StringArgumentType.getString(commandContext, "email"),
                                                StringArgumentType.getString(commandContext, "confirmEmail")
                                            )
                                        )
                                    }
                            )
                    )
            )

    private fun emailChange(context: HyperChatBrigadierContext) =
        BrigadierCommand.literalArgumentBuilder("change")
            .then(
                word("currentPassword")
                    .then(
                        word("oldEmail")
                            .then(
                                word("newEmail")
                                    .executes { commandContext ->
                                        context.execute(
                                            commandContext.source,
                                            args = arrayOf(
                                                "change",
                                                StringArgumentType.getString(commandContext, "currentPassword"),
                                                StringArgumentType.getString(commandContext, "oldEmail"),
                                                StringArgumentType.getString(commandContext, "newEmail")
                                            )
                                        )
                                    }
                            )
                    )
            )

    private fun emailShow(context: HyperChatBrigadierContext) =
        BrigadierCommand.literalArgumentBuilder("show")
            .then(
                word("currentPassword")
                    .executes { commandContext ->
                        context.execute(
                            commandContext.source,
                            args = arrayOf(
                                "show",
                                StringArgumentType.getString(commandContext, "currentPassword")
                            )
                        )
                    }
            )

    private fun emailRecovery(context: HyperChatBrigadierContext) =
        BrigadierCommand.literalArgumentBuilder("recovery")
            .then(
                word("email")
                    .executes { commandContext ->
                        context.execute(
                            commandContext.source,
                            args = arrayOf(
                                "recovery",
                                StringArgumentType.getString(commandContext, "email")
                            )
                        )
                    }
            )

    private fun emailCode(context: HyperChatBrigadierContext) =
        BrigadierCommand.literalArgumentBuilder("code")
            .then(
                word("verificationCode")
                    .executes { commandContext ->
                        context.execute(
                            commandContext.source,
                            args = arrayOf(
                                "code",
                                StringArgumentType.getString(commandContext, "verificationCode")
                            )
                        )
                    }
            )

    private fun emailSetPassword(context: HyperChatBrigadierContext) =
        BrigadierCommand.literalArgumentBuilder("setpassword")
            .then(
                word("newPassword")
                    .then(
                        word("confirmPassword")
                            .executes { commandContext ->
                                context.execute(
                                    commandContext.source,
                                    args = arrayOf(
                                        "setpassword",
                                        StringArgumentType.getString(commandContext, "newPassword"),
                                        StringArgumentType.getString(commandContext, "confirmPassword")
                                    )
                                )
                            }
                    )
            )

    private fun totpAdd(context: HyperChatBrigadierContext, literal: String) =
        BrigadierCommand.literalArgumentBuilder(literal)
            .then(
                word("password")
                    .executes { commandContext ->
                        context.execute(
                            commandContext.source,
                            args = arrayOf(
                                literal,
                                StringArgumentType.getString(commandContext, "password")
                            )
                        )
                    }
            )

    private fun totpConfirm(context: HyperChatBrigadierContext) =
        BrigadierCommand.literalArgumentBuilder("confirm")
            .then(
                word("code")
                    .executes { commandContext ->
                        context.execute(
                            commandContext.source,
                            args = arrayOf(
                                "confirm",
                                StringArgumentType.getString(commandContext, "code")
                            )
                        )
                    }
            )

    private fun totpRemove(context: HyperChatBrigadierContext, literal: String) =
        BrigadierCommand.literalArgumentBuilder(literal)
            .then(
                word("password")
                    .then(
                        word("code")
                            .executes { commandContext ->
                                context.execute(
                                    commandContext.source,
                                    args = arrayOf(
                                        literal,
                                        StringArgumentType.getString(commandContext, "password"),
                                        StringArgumentType.getString(commandContext, "code")
                                    )
                                )
                            }
                    )
            )

    private fun doublePasswordCommand(
        name: String,
        firstArgumentName: String,
        secondArgumentName: String
    ): HyperChatBrigadierRegistration {
        return HyperChatBrigadierRegistration { context ->
            context.literal(name)
                .executes { commandContext ->
                    context.execute(commandContext.source)
                }
                .then(
                    word(firstArgumentName)
                        .then(
                            word(secondArgumentName)
                                .executes { commandContext ->
                                    context.execute(
                                        commandContext.source,
                                        args = arrayOf(
                                            StringArgumentType.getString(commandContext, firstArgumentName),
                                            StringArgumentType.getString(commandContext, secondArgumentName)
                                        )
                                    )
                                }
                        )
                )
        }
    }

    private fun word(name: String): RequiredArgumentBuilder<CommandSource, String> {
        return BrigadierCommand.requiredArgumentBuilder(name, StringArgumentType.word())
    }

    private fun plainCommand(name: String): HyperChatBrigadierRegistration {
        return HyperChatBrigadierRegistration { context ->
            context.literal(name)
                .executes { commandContext ->
                    context.execute(commandContext.source)
                }
        }
    }
}
