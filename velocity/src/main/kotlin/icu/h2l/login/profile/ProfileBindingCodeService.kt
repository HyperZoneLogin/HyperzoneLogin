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
import icu.h2l.api.profile.HyperZoneProfileService
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.database.BindingCodeStore
import icu.h2l.login.message.MessageKeys
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class ProfileBindingCodeService(
    private val repository: BindingCodeStore,
    private val profileService: HyperZoneProfileService
) {
    data class Result(
        val success: Boolean,
        val message: Component
    )

    private val secureRandom = SecureRandom()
    private val codeLocks = ConcurrentHashMap<String, ReentrantLock>()

    private fun messages() = runCatching {
        HyperZoneLoginMain.getInstance().messageService
    }.getOrNull()

    fun generate(player: HyperZonePlayer): Result {
        val messages = messages()
        val attachedProfile = profileService.getAttachedProfile(player)
            ?: return Result(
                false,
                messages?.render(player, MessageKeys.BindCode.GENERATE_NO_PROFILE)
                    ?: Component.text("当前没有已绑定的档案，无法生成绑定码")
            )

        repeat(10) {
            val code = randomCode()
            if (repository.createOrReplace(code, attachedProfile.id, System.currentTimeMillis())) {
                return Result(true, generatedMessage(code, attachedProfile.name))
            }
        }

        return Result(
            false,
            messages?.render(player, MessageKeys.BindCode.GENERATE_FAILED)
                ?: Component.text("生成绑定码失败，请稍后再试")
        )
    }

    fun use(player: HyperZonePlayer, rawCode: String): Result {
        val messages = messages()
        if (!player.isVerified()) {
            return Result(
                false,
                messages?.render(player, MessageKeys.BindCode.USE_VERIFY_FIRST)
                    ?: Component.text("请先完成当前认证流程，再使用绑定码")
            )
        }
        if (player.hasAttachedProfile()) {
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
                val profileId = repository.findProfileId(code)
                    ?: return@withLock Result(
                        false,
                        messages?.render(player, MessageKeys.BindCode.USE_INVALID)
                            ?: Component.text("绑定码不存在、已过期或已被使用")
                    )

                val attachedProfile = try {
                    profileService.bindSubmittedCredentials(player, profileId)
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

                if (!repository.consume(code)) {
                    return@withLock Result(
                        false,
                        messages?.render(player, MessageKeys.BindCode.USE_CONSUME_FAILED)
                            ?: Component.text("绑定已写入，但销毁绑定码失败，请联系管理员")
                    )
                }

                profileService.attachProfile(player, attachedProfile.id)
                    ?: return@withLock Result(
                        false,
                        messages?.render(player, MessageKeys.BindCode.USE_ATTACH_FAILED)
                            ?: Component.text("绑定码已销毁，但 attach 档案失败，请联系管理员")
                    )

                Result(
                    true,
                    messages?.render(
                        player,
                        MessageKeys.BindCode.USE_SUCCESS,
                        HyperZoneMessagePlaceholder.text("profile_name", attachedProfile.name)
                    ) ?: Component.text("绑定成功，已关联到档案 ${attachedProfile.name}")
                )
            }
        } finally {
            if (!lock.isLocked && !lock.hasQueuedThreads()) {
                codeLocks.remove(code, lock)
            }
        }
    }

    private fun generatedMessage(code: String, profileName: String): Component {
        val messages = messages()
        if (messages == null) {
            return Component.empty()
                .append(Component.text("绑定码已生成，可发送给待绑定玩家：", NamedTextColor.YELLOW))
                .append(Component.newline())
                .append(
                    Component.text(code, NamedTextColor.AQUA, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.copyToClipboard(code))
                        .hoverEvent(HoverEvent.showText(Component.text("点击复制绑定码", NamedTextColor.GREEN)))
                        .insertion(code)
                )
                .append(Component.newline())
                .append(Component.text("目标档案：$profileName ；该绑定码使用一次后立即失效。", NamedTextColor.GRAY))
        }
        return Component.empty()
            .append(messages.render(MessageKeys.BindCode.GENERATED_HEADER))
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
                    MessageKeys.BindCode.GENERATED_FOOTER,
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

    companion object {
        private const val BIND_CODE_CHARS = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    }
}




