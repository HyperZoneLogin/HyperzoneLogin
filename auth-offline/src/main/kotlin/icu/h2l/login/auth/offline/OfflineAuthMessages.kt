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

package icu.h2l.login.auth.offline

import icu.h2l.api.message.HyperZoneMessagePlaceholder
import icu.h2l.api.message.HyperZoneMessageServiceProvider
import net.kyori.adventure.text.Component

object OfflineAuthMessages {
    private const val PREFIX = "§8[§6玩家系统§8] "
    private const val NAMESPACE = "auth-offline"

    val ONLY_PLAYER: Component
        get() = render("common.only-player", "${PREFIX}§c该命令只能由玩家执行")
    val NO_PERMISSION: Component
        get() = render("common.no-permission", "${PREFIX}§c没有权限")
    val DENIED_COMMAND: Component
        get() = render("common.denied-command", "${PREFIX}§c您需要先通过验证才能使用该命令！")
    val DENIED_CHAT: Component
        get() = render("common.denied-chat", "${PREFIX}§c您需要先通过验证才能聊天！")
    val NOT_IMPLEMENTED: Component
        get() = render("common.not-implemented", "${PREFIX}§7该命令功能暂未实现")

    val ALREADY_LOGGED_IN: Component
        get() = render("login.already-logged-in", "${PREFIX}§c你已经完成登录了！")
    val NOT_LOGGED_IN: Component
        get() = render("login.not-logged-in", "${PREFIX}§c你还未登录！")
    val UNREGISTERED: Component
        get() = render("common.unregistered", "${PREFIX}§c此用户名还未注册过")
    val UNREGISTERED_SIMPLE: Component
        get() = render("common.unregistered-simple", "§c尚未注册")
    val ATTACHED_PROFILE_MISSING: Component
        get() = render("common.attached-profile-missing", "§c未找到已绑定的游戏档案，无法完成本次认证")
    val PROFILE_ATTACH_FAILED_AFTER_LOGIN: Component
        get() = render("common.profile-attach-failed-after-login", "§c离线认证成功，但 Profile 绑定失败")

    val REGISTER_SUCCESS: Component
        get() = render("register.success", "${PREFIX}§a已成功注册，已为你自动登录")
    val REGISTER_FAILED: Component
        get() = render("register.failed", "${PREFIX}§c注册失败，请稍后再试")
    val OFFLINE_PASSWORD_ALREADY_SET: Component
        get() = render("register.password-already-set", "${PREFIX}§e你已设置过离线密码，无需重复操作")
    val REGISTER_USAGE: Component
        get() = render("register.usage", "${PREFIX}§e/register <密码> <再次输入密码>")
    val REGISTER_REQUEST: Component
        get() = render("register.request", "${PREFIX}§c请输入“/register <密码> <再次输入密码>”以注册")
    val REGISTER_BOUND_SUCCESS: Component
        get() = render("register.bound-success", "${PREFIX}§a检测到你已有档案，已自动绑定离线密码")
    val REGISTER_BIND_DENIED: Component
        get() = render("register.bind-denied", "${PREFIX}§c检测到你已有档案，但当前未完成其他验证，暂时无法设置离线密码")
    val REGISTER_BIND_PROFILE_MISSING: Component
        get() = render("register.bind-profile-missing", "${PREFIX}§c未找到已关联档案，暂时无法设置离线密码")
    val REGISTER_BIND_HINT: Component
        get() = render("register.bind-hint", "${PREFIX}§e检测到你已有档案，可直接使用 /register <密码> <再次输入密码> 设置离线密码")
    val REGISTER_BIND_PENDING: Component
        get() = render("register.bind-pending", "§a注册成功，但当前名称无法直接分配档案。请使用 /bindcode use <绑定码> 完成绑定")
    val REGISTER_BIND_PENDING_ERROR: Component
        get() = render("register.bind-pending-error", "§c注册成功，但等待绑定时出现错误")

    val LOGIN_USAGE: Component
        get() = render("login.usage", "${PREFIX}§e/login <密码> [验证码]")
    val LOGIN_REQUEST: Component
        get() = render("login.request", "${PREFIX}§c请输入“/login <密码>”以登录")
    val LOGIN_SUCCESS: Component
        get() = render("login.success", "${PREFIX}§a已成功登录！")
    val LOGIN_WRONG_PASSWORD: Component
        get() = render("login.wrong-password", "${PREFIX}§c错误的密码")
    val PASSWORD_MISMATCH: Component
        get() = render("password.mismatch", "${PREFIX}§c两次密码不一致")
    val PASSWORD_CHANGED: Component
        get() = render("password.changed", "${PREFIX}§a密码已成功修改！")
    val PASSWORD_CHANGE_AUTO_AUTHED: Component
        get() = render("password.changed-auto-authed", "${PREFIX}§a密码已成功修改！ §7已自动通过本次认证")
    val PASSWORD_UPDATE_FAILED: Component
        get() = render("password.update-failed", "§c密码更新失败，请稍后再试")
    val PASSWORD_RESET_FAILED: Component
        get() = render("password.reset-failed", "§c密码重置失败，请稍后再试")
    val CHANGE_PASSWORD_USAGE: Component
        get() = render("password.change-usage", "${PREFIX}§e/changepassword <旧密码> <新密码>")
    val LOGOUT_USAGE: Component
        get() = render("logout.usage", "${PREFIX}§e/logout")
    val LOGOUT_SUCCESS: Component
        get() = render("logout.success", "${PREFIX}§a已成功登出，请重新登录")
    val SESSION_AUTO_LOGIN: Component
        get() = render("session.auto-login", "${PREFIX}§a欢迎回来，已根据有效会话自动登录")
    val SESSION_INVALID: Component
        get() = render("session.invalid", "${PREFIX}§e检测到旧会话已失效，请重新输入密码登录")
    val UNREGISTER_USAGE: Component
        get() = render("unregister.usage", "${PREFIX}§e/unregister <密码>")
    val UNREGISTER_SUCCESS: Component
        get() = render("unregister.success", "${PREFIX}§a账号已注销")
    val UNREGISTER_FAILED: Component
        get() = render("unregister.failed", "§c注销失败，请稍后再试")

    val EMAIL_USAGE: Component
        get() = render("email.usage", "${PREFIX}§e/email <add|change|show|recovery|code|setpassword> ...")
    val EMAIL_DISABLED: Component
        get() = render("email.disabled", "${PREFIX}§c当前服务器未启用邮箱功能")
    val EMAIL_INVALID: Component
        get() = render("email.invalid", "${PREFIX}§c无效的邮箱地址")
    val EMAIL_ADDED: Component
        get() = render("email.added", "${PREFIX}§a邮箱已绑定")
    val EMAIL_CHANGED: Component
        get() = render("email.changed", "${PREFIX}§a邮箱已修改")
    val EMAIL_ALREADY_USED: Component
        get() = render("email.already-used", "${PREFIX}§c该邮箱已被其他账号使用")
    val EMAIL_NOT_SET: Component
        get() = render("email.not-set", "${PREFIX}§e当前账号尚未绑定邮箱")
    val EMAIL_ADD_USAGE: Component
        get() = render("email.add-usage", "${PREFIX}§e/email add <当前密码> <邮箱> <再次输入邮箱>")
    val EMAIL_CHANGE_USAGE: Component
        get() = render("email.change-usage", "${PREFIX}§e/email change <当前密码> <旧邮箱> <新邮箱>")
    val EMAIL_SHOW_USAGE: Component
        get() = render("email.show-usage", "${PREFIX}§e/email show <当前密码>")
    val EMAIL_RECOVERY_USAGE: Component
        get() = render("email.recovery-usage", "${PREFIX}§e/email recovery <邮箱>")
    val EMAIL_CODE_USAGE: Component
        get() = render("email.code-usage", "${PREFIX}§e/email code <验证码>")
    val EMAIL_SETPASSWORD_USAGE: Component
        get() = render("email.setpassword-usage", "${PREFIX}§e/email setpassword <新密码> <再次输入新密码>")
    val EMAIL_MISMATCH: Component
        get() = render("email.mismatch", "§c两次输入的邮箱不一致")
    val EMAIL_BIND_FAILED: Component
        get() = render("email.bind-failed", "§c邮箱绑定失败，请稍后再试")
    val EMAIL_OLD_MISMATCH: Component
        get() = render("email.old-mismatch", "§c旧邮箱不匹配")
    val EMAIL_CHANGE_FAILED: Component
        get() = render("email.change-failed", "§c邮箱修改失败，请稍后再试")
    val RECOVERY_HINT: Component
        get() = render("email.recovery-hint", "${PREFIX}§e忘记密码？可使用 /email recovery <邮箱> 请求找回")
    val RECOVERY_EMAIL_SENT: Component
        get() = render("email.recovery-email-sent", "${PREFIX}§a找回邮件已发送，请检查你的邮箱")
    val RECOVERY_CODE_CORRECT: Component
        get() = render("email.recovery-code-correct", "${PREFIX}§a验证码正确，请立即使用 /email setpassword <新密码> <再次输入新密码>")
    val RECOVERY_CODE_INCORRECT: Component
        get() = render("email.recovery-code-incorrect", "${PREFIX}§c验证码不正确")
    val RECOVERY_CODE_EXPIRED: Component
        get() = render("email.recovery-code-expired", "${PREFIX}§c验证码已过期，请重新使用 /email recovery <邮箱>")
    val RECOVERY_CODE_NOT_REQUESTED: Component
        get() = render("email.recovery-code-not-requested", "${PREFIX}§c你还没有请求找回验证码")
    val RECOVERY_PASSWORD_WINDOW_EXPIRED: Component
        get() = render("email.recovery-password-window-expired", "${PREFIX}§c当前不可通过恢复码修改密码，请重新申请找回")
    val RECOVERY_CODE_WRITE_FAILED: Component
        get() = render("email.recovery-code-write-failed", "§c写入恢复码失败，请稍后再试")
    val RECOVERY_STATE_UPDATE_FAILED: Component
        get() = render("email.recovery-state-update-failed", "§c恢复码状态更新失败，请稍后再试")
    val OLD_PASSWORD_WRONG: Component
        get() = render("password.old-wrong", "${PREFIX}§c旧密码错误")
    val PASSWORD_WRONG: Component
        get() = render("password.wrong", "${PREFIX}§c密码错误")

    val TOTP_USAGE: Component
        get() = render("totp.usage", "${PREFIX}§e/totp <add|confirm|remove> ...")
    val TOTP_ADD_USAGE: Component
        get() = render("totp.add-usage", "${PREFIX}§e/totp add <密码>")
    val TOTP_CONFIRM_USAGE: Component
        get() = render("totp.confirm-usage", "${PREFIX}§e/totp confirm <验证码>")
    val TOTP_REMOVE_USAGE: Component
        get() = render("totp.remove-usage", "${PREFIX}§e/totp remove <密码> <验证码>")
    val TOTP_DISABLED_BY_CONFIG: Component
        get() = render("totp.disabled-by-config", "${PREFIX}§c当前服务器未启用 TOTP 二步验证功能")
    val TOTP_ALREADY_ENABLED: Component
        get() = render("totp.already-enabled", "${PREFIX}§e当前账号已启用 TOTP 二步验证")
    val TOTP_NOT_ENABLED: Component
        get() = render("totp.not-enabled", "${PREFIX}§e当前账号尚未启用 TOTP 二步验证")
    val TOTP_PENDING_NOT_FOUND: Component
        get() = render("totp.pending-not-found", "${PREFIX}§e未找到待确认的 TOTP 配置，请先使用 /totp add <密码>")
    val TOTP_INVALID_CODE: Component
        get() = render("totp.invalid-code", "${PREFIX}§cTOTP 验证码错误或已被使用")
    val TOTP_ENABLED: Component
        get() = render("totp.enabled", "${PREFIX}§a已成功启用 TOTP 二步验证")
    val TOTP_DISABLED: Component
        get() = render("totp.disabled", "${PREFIX}§a已成功关闭 TOTP 二步验证")
    val TOTP_ENABLE_FAILED: Component
        get() = render("totp.enable-failed", "§c二步验证启用失败，请稍后再试")
    val TOTP_DISABLE_FAILED: Component
        get() = render("totp.disable-failed", "§c二步验证关闭失败，请稍后再试")
    val TOTP_LOGIN_REQUIRED: Component
        get() = render("totp.login-required", "${PREFIX}§e该账号已启用 TOTP，请使用 /login <密码> <验证码> 完成登录")
    val TOTP_LOGIN_HINT: Component
        get() = render("totp.login-hint", "${PREFIX}§7已启用 TOTP，请使用 /login <密码> <验证码> 登录")
    val PENDING_BIND_PROMPT: Component
        get() = render("prompt.pending-bind", "§8[§6玩家系统§8] §7当前离线注册信息已暂存，请使用 /bindcode use <绑定码> 完成绑定")
    val CHANGE_PASSWORD_PROMPT: Component
        get() = render("prompt.change-password", "§8[§6玩家系统§8] §7如需修改密码：/changepassword <旧密码> <新密码>")
    val EMAIL_ADD_PROMPT: Component
        get() = render("prompt.email-add", "§8[§6玩家系统§8] §7可使用 /email add <当前密码> <邮箱> <再次输入邮箱> 绑定邮箱")
    val TOTP_REMOVE_PROMPT: Component
        get() = render("prompt.totp-remove", "§8[§6玩家系统§8] §7已启用 TOTP，可使用 /totp remove <密码> <验证码> 关闭")
    val TOTP_ADD_PROMPT: Component
        get() = render("prompt.totp-add", "§8[§6玩家系统§8] §7可使用 /totp add <密码> 启用二步验证")

    fun loginBlocked(seconds: Long): Component {
        return render(
            "login.blocked",
            "${PREFIX}§c由于登录失败次数过多，请在 ${formatDuration(seconds)} 后再试",
            HyperZoneMessagePlaceholder.text("duration", formatDuration(seconds))
        )
    }

    fun wrongPasswordWithRemainingAttempts(remainingAttempts: Int): Component {
        return render(
            "login.wrong-password-with-remaining-attempts",
            "${PREFIX}§c错误的密码，你还有 $remainingAttempts 次尝试机会",
            HyperZoneMessagePlaceholder.text("remaining_attempts", remainingAttempts)
        )
    }

    fun unsafePassword(min: Int, max: Int): Component {
        return render(
            "password.unsafe",
            "${PREFIX}§c密码长度必须在 $min 到 $max 个字符之间",
            HyperZoneMessagePlaceholder.text("min", min),
            HyperZoneMessagePlaceholder.text("max", max)
        )
    }

    fun passwordContainsName(name: String): Component {
        return render(
            "password.contains-name",
            "${PREFIX}§c你不能使用包含用户名“$name”的密码",
            HyperZoneMessagePlaceholder.text("name", name)
        )
    }

    fun emailShow(email: String): Component {
        return render(
            "email.show",
            "${PREFIX}§a当前绑定邮箱：§f$email",
            HyperZoneMessagePlaceholder.text("email", email)
        )
    }

    fun recoveryCooldown(seconds: Long): Component {
        return render(
            "email.recovery-cooldown",
            "${PREFIX}§c恢复邮件刚刚发送过，请在 ${formatDuration(seconds)} 后再试",
            HyperZoneMessagePlaceholder.text("duration", formatDuration(seconds))
        )
    }

    fun recoveryCodeAttemptsExceeded(): Component {
        return render(
            "email.recovery-code-attempts-exceeded",
            "${PREFIX}§c恢复码输错次数已达上限，请重新使用 /email recovery <邮箱> 获取新的验证码"
        )
    }

    fun recoverySendFailure(reason: String?): Component {
        return if (reason.isNullOrBlank()) {
            render("email.recovery-send-failure", "${PREFIX}§c恢复邮件发送失败，请联系管理员检查邮箱配置")
        } else {
            render(
                "email.recovery-send-failure-with-reason",
                "${PREFIX}§c恢复邮件发送失败：$reason",
                HyperZoneMessagePlaceholder.text("reason", reason)
            )
        }
    }

    fun recoveryEmailSentWithDiagnostic(diagnostic: String): Component {
        return render(
            "email.recovery-email-sent-with-diagnostic",
            "${PREFIX}§a找回邮件已发送，请检查你的邮箱 §7($diagnostic)",
            HyperZoneMessagePlaceholder.text("diagnostic", diagnostic)
        )
    }

    fun totpSetupGenerated(secret: String, otpAuthUrl: String): Component {
        return render(
            "totp.setup-generated",
            "${PREFIX}§a已生成 TOTP 密钥\n§7Secret: §f$secret\n§7OTPAuth: §f$otpAuthUrl\n§e请在验证器 App 中添加后，使用 /totp confirm <验证码> 完成启用",
            HyperZoneMessagePlaceholder.text("secret", secret),
            HyperZoneMessagePlaceholder.text("otp_auth_url", otpAuthUrl)
        )
    }

    fun invalidRecoveryDeliveryMode(mode: String): Component {
        return render(
            "email.invalid-recovery-delivery-mode",
            "${PREFIX}§e当前恢复码投递模式为 $mode，验证码内容已写入服务端日志",
            HyperZoneMessagePlaceholder.text("mode", mode)
        )
    }

    private fun render(key: String, fallback: String, vararg placeholders: HyperZoneMessagePlaceholder): Component {
        val service = HyperZoneMessageServiceProvider.getOrNull()
        return if (service != null) {
            service.render("$NAMESPACE.$key", *placeholders)
        } else {
            Component.text(applyFallbackPlaceholders(fallback, placeholders))
        }
    }

    private fun applyFallbackPlaceholders(template: String, placeholders: Array<out HyperZoneMessagePlaceholder>): String {
        return placeholders.fold(template) { current, placeholder ->
            val replacement = when (placeholder) {
                is HyperZoneMessagePlaceholder.Text -> placeholder.value
                is HyperZoneMessagePlaceholder.ComponentValue -> placeholder.value.toString()
            }
            current.replace("<${placeholder.name}>", replacement)
        }
    }

    private fun formatDuration(seconds: Long): String {
        if (seconds <= 60) {
            return "${seconds}秒"
        }

        val minutes = seconds / 60
        val remainSeconds = seconds % 60
        return if (remainSeconds == 0L) {
            "${minutes}分"
        } else {
            "${minutes}分${remainSeconds}秒"
        }
    }
}

