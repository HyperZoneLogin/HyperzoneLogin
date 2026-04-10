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

object OfflineAuthMessages {
    private const val PREFIX = "§8[§6玩家系统§8] "

    const val ONLY_PLAYER = "${PREFIX}§c该命令只能由玩家执行"
    const val NO_PERMISSION = "${PREFIX}§c没有权限"
    const val DENIED_COMMAND = "${PREFIX}§c您需要先通过验证才能使用该命令！"
    const val DENIED_CHAT = "${PREFIX}§c您需要先通过验证才能聊天！"
    const val ALREADY_LOGGED_IN = "${PREFIX}§c你已经完成登录了！"
    const val NOT_LOGGED_IN = "${PREFIX}§c你还未登录！"
    const val UNREGISTERED = "${PREFIX}§c此用户名还未注册过"
    const val REGISTER_SUCCESS = "${PREFIX}§a已成功注册，已为你自动登录"
    const val REGISTER_FAILED = "${PREFIX}§c注册失败，请稍后再试"
    const val REGISTER_REPEAT = "${PREFIX}§c此用户已经注册过，无法重复注册"
    const val REGISTER_USAGE = "${PREFIX}§e/register <密码> <再次输入密码>"
    const val REGISTER_REQUEST = "${PREFIX}§c请输入“/register <密码> <再次输入密码>”以注册"
    const val LOGIN_USAGE = "${PREFIX}§e/login <密码>"
    const val LOGIN_REQUEST = "${PREFIX}§c请输入“/login <密码>”以登录"
    const val LOGIN_SUCCESS = "${PREFIX}§a已成功登录！"
    const val LOGIN_WRONG_PASSWORD = "${PREFIX}§c错误的密码"
    const val PASSWORD_MISMATCH = "${PREFIX}§c两次密码不一致"
    const val PASSWORD_CHANGED = "${PREFIX}§a密码已成功修改！"
    const val CHANGE_PASSWORD_USAGE = "${PREFIX}§e/changepassword <旧密码> <新密码>"
    const val BIND_USAGE = "${PREFIX}§e/bind <密码> <再次输入密码>"
    const val BIND_SUCCESS = "${PREFIX}§a绑定成功"
    const val LOGOUT_SUCCESS = "${PREFIX}§a已成功登出，请重新登录"
    const val SESSION_AUTO_LOGIN = "${PREFIX}§a欢迎回来，已根据有效会话自动登录"
    const val SESSION_INVALID = "${PREFIX}§e检测到旧会话已失效，请重新输入密码登录"
    const val UNREGISTER_USAGE = "${PREFIX}§e/unregister <密码>"
    const val UNREGISTER_SUCCESS = "${PREFIX}§a账号已注销"
    const val EMAIL_USAGE = "${PREFIX}§e/email <add|change|show|recovery|code|setpassword> ..."
    const val EMAIL_DISABLED = "${PREFIX}§c当前服务器未启用邮箱功能"
    const val EMAIL_INVALID = "${PREFIX}§c无效的邮箱地址"
    const val EMAIL_ADDED = "${PREFIX}§a邮箱已绑定"
    const val EMAIL_CHANGED = "${PREFIX}§a邮箱已修改"
    const val EMAIL_ALREADY_USED = "${PREFIX}§c该邮箱已被其他账号使用"
    const val EMAIL_NOT_SET = "${PREFIX}§e当前账号尚未绑定邮箱"
    const val EMAIL_ADD_USAGE = "${PREFIX}§e/email add <当前密码> <邮箱> <再次输入邮箱>"
    const val EMAIL_CHANGE_USAGE = "${PREFIX}§e/email change <当前密码> <旧邮箱> <新邮箱>"
    const val EMAIL_SHOW_USAGE = "${PREFIX}§e/email show <当前密码>"
    const val EMAIL_RECOVERY_USAGE = "${PREFIX}§e/email recovery <邮箱>"
    const val EMAIL_CODE_USAGE = "${PREFIX}§e/email code <验证码>"
    const val EMAIL_SETPASSWORD_USAGE = "${PREFIX}§e/email setpassword <新密码> <再次输入新密码>"
    const val RECOVERY_HINT = "${PREFIX}§e忘记密码？可使用 /email recovery <邮箱> 请求找回"
    const val RECOVERY_EMAIL_SENT = "${PREFIX}§a找回邮件已发送，请检查你的邮箱"
    const val RECOVERY_CODE_CORRECT = "${PREFIX}§a验证码正确，请立即使用 /email setpassword <新密码> <再次输入新密码>"
    const val RECOVERY_CODE_INCORRECT = "${PREFIX}§c验证码不正确"
    const val RECOVERY_CODE_EXPIRED = "${PREFIX}§c验证码已过期，请重新使用 /email recovery <邮箱>"
    const val RECOVERY_CODE_NOT_REQUESTED = "${PREFIX}§c你还没有请求找回验证码"
    const val RECOVERY_PASSWORD_WINDOW_EXPIRED = "${PREFIX}§c当前不可通过恢复码修改密码，请重新申请找回"
    const val OLD_PASSWORD_WRONG = "${PREFIX}§c旧密码错误"
    const val PASSWORD_WRONG = "${PREFIX}§c密码错误"

    fun loginBlocked(seconds: Long): String {
        return "${PREFIX}§c由于登录失败次数过多，请在 ${formatDuration(seconds)} 后再试"
    }

    fun wrongPasswordWithRemainingAttempts(remainingAttempts: Int): String {
        return "${PREFIX}§c错误的密码，你还有 $remainingAttempts 次尝试机会"
    }

    fun unsafePassword(min: Int, max: Int): String {
        return "${PREFIX}§c密码长度必须在 $min 到 $max 个字符之间"
    }

    fun passwordContainsName(name: String): String {
        return "${PREFIX}§c你不能使用包含用户名“$name”的密码"
    }

    fun emailShow(email: String): String {
        return "${PREFIX}§a当前绑定邮箱：§f$email"
    }

    fun recoveryCooldown(seconds: Long): String {
        return "${PREFIX}§c恢复邮件刚刚发送过，请在 ${formatDuration(seconds)} 后再试"
    }

    fun recoveryCodeAttemptsExceeded(): String {
        return "${PREFIX}§c恢复码输错次数已达上限，请重新使用 /email recovery <邮箱> 获取新的验证码"
    }

    fun recoverySendFailure(reason: String?): String {
        return if (reason.isNullOrBlank()) {
            "${PREFIX}§c恢复邮件发送失败，请联系管理员检查邮箱配置"
        } else {
            "${PREFIX}§c恢复邮件发送失败：$reason"
        }
    }

    fun invalidRecoveryDeliveryMode(mode: String): String {
        return "${PREFIX}§e当前恢复码投递模式为 $mode，验证码内容已写入服务端日志"
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

