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

import icu.h2l.api.message.HyperZoneMessagePlaceholder
import icu.h2l.api.player.HyperZonePlayer
import icu.h2l.api.profile.HyperZoneUserService
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.database.helper.BindingCodeStore
import icu.h2l.login.manager.LoginManager
import icu.h2l.login.message.MessageKeys
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class UserBindingCodeService(
    private val store: BindingCodeStore,
    private val userService: HyperZoneUserService,
    private val loginManager: LoginManager,
) {
    data class Result(
        val success: Boolean,
        val message: Component
    )

    private val secureRandom = SecureRandom()
    private val codeLocks = ConcurrentHashMap<String, ReentrantLock>()
    private val profileLocks = ConcurrentHashMap<String, ReentrantLock>()

    private fun messages() = runCatching {
        HyperZoneLoginMain.getInstance().messageService
    }.getOrNull()

    fun generate(player: HyperZonePlayer): Result {
        val messages = messages()
        val attachedUser = userService.getAttachedUser(player)
            ?: return Result(
                false,
                messages?.render(player, MessageKeys.BindCode.GENERATE_NO_USER)
                    ?: Component.text("当前没有已绑定的档案，无法生成绑定码")
            )

        val lockKey = attachedUser.id.toString()
        val lock = profileLocks.computeIfAbsent(lockKey) { ReentrantLock() }
        try {
            return lock.withLock {
                store.findCode(attachedUser.id)?.let { existingCode ->
                    return@withLock Result(true, existingCodeMessage(existingCode, displayName(player)))
                }

                repeat(10) {
                    val code = randomCode()
                    if (store.replace(code, attachedUser.id, System.currentTimeMillis())) {
                        return@withLock Result(true, generatedMessage(code, displayName(player)))
                    }
                }

                Result(
                    false,
                    messages?.render(player, MessageKeys.BindCode.GENERATE_FAILED)
                        ?: Component.text("生成绑定码失败，请稍后再试")
                )
            }
        } finally {
            if (!lock.isLocked && !lock.hasQueuedThreads()) {
                profileLocks.remove(lockKey, lock)
            }
        }
    }

    fun use(player: HyperZonePlayer, rawCode: String): Result {
        val messages = messages()
        if (player.hasAttachedUser()) {
            return Result(
                false,
                messages?.render(player, MessageKeys.BindCode.USE_ALREADY_BOUND)
                    ?: Component.text("你当前已经绑定正式档案，无需再次使用绑定码")
            )
        }
        if (player.getSubmittedCredentials().isEmpty()) {
            return Result(
                false,
                messages?.render(player, MessageKeys.BindCode.USE_NO_CREDENTIALS)
                    ?: Component.text("当前没有可绑定的认证凭证")
            )
        }

        val code = normalizeCode(rawCode)
        if (code.isBlank()) {
            return Result(
                false,
                messages?.render(player, MessageKeys.BindCode.USE_EMPTY)
                    ?: Component.text("绑定码不能为空")
            )
        }

        val lock = codeLocks.computeIfAbsent(code) { ReentrantLock() }
        try {
            return lock.withLock {
                val userUUID = store.findProfileId(code)
                    ?: return@withLock Result(
                        false,
                        messages?.render(player, MessageKeys.BindCode.USE_INVALID)
                            ?: Component.text("绑定码不存在、已过期或已被使用")
                    )

                val attachedUser = try {
                    userService.bindSubmittedCredentials(player, userUUID)
                } catch (t: Throwable) {
                    val reason = t.message?.takeUnless { it.isBlank() }
                    return@withLock Result(
                        false,
                        if (reason == null && messages != null) {
                            messages.render(player, MessageKeys.BindCode.USE_BIND_FAILED)
                        } else if (reason != null && messages != null) {
                            messages.render(
                                player,
                                MessageKeys.BindCode.USE_BIND_FAILED_WITH_REASON,
                                HyperZoneMessagePlaceholder.text("reason", reason)
                            )
                        } else {
                            Component.text(reason ?: "绑定失败，请稍后再试")
                        }
                    )
                }

                if (!store.consume(code)) {
                    return@withLock Result(
                        false,
                        messages?.render(player, MessageKeys.BindCode.USE_CONSUME_FAILED)
                            ?: Component.text("绑定已写入，但销毁绑定码失败，请联系管理员")
                    )
                }

                val attached = try {
                    attachUser(player, attachedUser.id)
                } catch (throwable: Throwable) {
                    val reason = throwable.message?.takeUnless { it.isBlank() }
                    return@withLock Result(
                        false,
                        if (reason != null) {
                            messages?.render(
                                player,
                                MessageKeys.BindCode.USE_ATTACH_FAILED_WITH_REASON,
                                HyperZoneMessagePlaceholder.text("reason", reason)
                            ) ?: Component.text("绑定码已销毁，但 attach 档案失败：$reason")
                        } else {
                            messages?.render(player, MessageKeys.BindCode.USE_ATTACH_FAILED)
                                ?: Component.text("绑定码已销毁，但 attach 档案失败，请联系管理员")
                        }
                    )
                }
                attached ?: return@withLock Result(
                    false,
                    messages?.render(player, MessageKeys.BindCode.USE_ATTACH_FAILED)
                        ?: Component.text("绑定码已销毁，但 attach 档案失败，请联系管理员")
                )

                Result(
                    true,
                    messages?.render(
                        player,
                        MessageKeys.BindCode.USE_SUCCESS,
                        HyperZoneMessagePlaceholder.text("profile_name", displayName(player))
                    ) ?: Component.text("绑定成功，已关联到档案 ${displayName(player)}")
                )
            }
        } finally {
            if (!lock.isLocked && !lock.hasQueuedThreads()) {
                codeLocks.remove(code, lock)
            }
        }
    }

    private fun generatedMessage(code: String, profileName: String): Component {
        return bindingCodeMessage(
            code = code,
            profileName = profileName,
            headerKey = MessageKeys.BindCode.GENERATED_HEADER,
            footerKey = MessageKeys.BindCode.GENERATED_FOOTER,
            defaultHeader = "绑定码已生成，可发送给待绑定玩家：",
            defaultFooter = "目标档案：$profileName ；该绑定码使用一次后立即失效。"
        )
    }

    private fun existingCodeMessage(code: String, profileName: String): Component {
        return bindingCodeMessage(
            code = code,
            profileName = profileName,
            headerKey = MessageKeys.BindCode.EXISTING_HEADER,
            footerKey = MessageKeys.BindCode.EXISTING_FOOTER,
            defaultHeader = "你已经有一个可用的绑定码，可直接让待绑定玩家使用：",
            defaultFooter = "目标档案：$profileName ；无需重新生成，该绑定码使用一次后立即失效。"
        )
    }

    private fun bindingCodeMessage(
        code: String,
        profileName: String,
        headerKey: String,
        footerKey: String,
        defaultHeader: String,
        defaultFooter: String
    ): Component {
        val messages = messages()
        if (messages == null) {
            return Component.empty()
                .append(Component.text(defaultHeader, NamedTextColor.YELLOW))
                .append(Component.newline())
                .append(
                    Component.text(code, NamedTextColor.AQUA, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.copyToClipboard(code))
                        .hoverEvent(HoverEvent.showText(Component.text("点击复制绑定码", NamedTextColor.GREEN)))
                        .insertion(code)
                )
                .append(Component.newline())
                .append(Component.text(defaultFooter, NamedTextColor.GRAY))
        }
        return Component.empty()
            .append(messages.render(headerKey))
            .append(Component.newline())
            .append(
                Component.text(code, NamedTextColor.AQUA, TextDecoration.BOLD)
                    .clickEvent(ClickEvent.copyToClipboard(code))
                    .hoverEvent(HoverEvent.showText(messages.render(MessageKeys.BindCode.GENERATED_HOVER_COPY)))
                    .insertion(code)
            )
            .append(Component.newline())
            .append(
                messages.render(
                    footerKey,
                    HyperZoneMessagePlaceholder.text("profile_name", profileName)
                )
            )
    }

    private fun randomCode(length: Int = 10): String {
        return buildString(length) {
            repeat(length) {
                append(BIND_CODE_CHARS[secureRandom.nextInt(BIND_CODE_CHARS.length)])
            }
        }
    }

    private fun normalizeCode(rawCode: String): String {
        return rawCode.trim().uppercase(Locale.ROOT)
    }

    private fun attachUser(player: HyperZonePlayer, userUUID: UUID) =
        loginManager.attachUser(player, userUUID)

    companion object {
        private const val BIND_CODE_CHARS = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    }

    private fun displayName(player: HyperZonePlayer): String {
        return player.activeInGameProfile?.name ?: player.userUUID?.toString() ?: "<unknown>"
    }
}




