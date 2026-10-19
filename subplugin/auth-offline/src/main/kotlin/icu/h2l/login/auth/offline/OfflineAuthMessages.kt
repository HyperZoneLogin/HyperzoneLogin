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
    private const val NAMESPACE = "auth-offline"

    val ONLY_PLAYER: Component
        get() = render("common.only-player")
    val NO_PERMISSION: Component
        get() = render("common.no-permission")
    val DENIED_COMMAND: Component
        get() = render("common.denied-command")
    val DENIED_CHAT: Component
        get() = render("common.denied-chat")
    val NOT_IMPLEMENTED: Component
        get() = render("common.not-implemented")

    val ALREADY_LOGGED_IN: Component
        get() = render("login.already-logged-in")
    val NOT_LOGGED_IN: Component
        get() = render("login.not-logged-in")
    val UNREGISTERED: Component
        get() = render("common.unregistered")
    val UNREGISTERED_SIMPLE: Component
        get() = render("common.unregistered-simple")
    val ATTACHED_PROFILE_MISSING: Component
        get() = render("common.attached-profile-missing")
    val PROFILE_ATTACH_FAILED_AFTER_LOGIN: Component
        get() = render("common.profile-attach-failed-after-login")

    val REGISTER_DISABLED: Component
        get() = render("register.disabled")
    val REGISTER_SUCCESS: Component
        get() = render("register.success")
    val REGISTER_FAILED: Component
        get() = render("register.failed")
    val OFFLINE_PASSWORD_ALREADY_SET: Component
        get() = render("register.password-already-set")
    val REGISTER_USAGE: Component
        get() = render("register.usage")
    val REGISTER_REQUEST: Component
        get() = render("register.request")
    val REGISTER_BOUND_SUCCESS: Component
        get() = render("register.bound-success")
    val REGISTER_BIND_DENIED: Component
        get() = render("register.bind-denied")
    val REGISTER_BIND_PROFILE_MISSING: Component
        get() = render("register.bind-profile-missing")
    val REGISTER_BIND_PENDING: Component
        get() = render("register.bind-pending")
    val REGISTER_BIND_PENDING_ERROR: Component
        get() = render("register.bind-pending-error")

    val LOGIN_USAGE: Component
        get() = render("login.usage")
    val LOGIN_REQUEST: Component
        get() = render("login.request")
    val LOGIN_SUCCESS: Component
        get() = render("login.success")
    val LOGIN_WRONG_PASSWORD: Component
        get() = render("login.wrong-password")
    val PASSWORD_MISMATCH: Component
        get() = render("password.mismatch")
    val PASSWORD_CHANGED: Component
        get() = render("password.changed")
    val PASSWORD_CHANGE_AUTO_AUTHED: Component
        get() = render("password.changed-auto-authed")
    val PASSWORD_UPDATE_FAILED: Component
        get() = render("password.update-failed")
    val PASSWORD_RESET_FAILED: Component
        get() = render("password.reset-failed")
    val CHANGE_PASSWORD_USAGE: Component
        get() = render("password.change-usage")
    val LOGOUT_USAGE: Component
        get() = render("logout.usage")
    val LOGOUT_SUCCESS: Component
        get() = render("logout.success")
    val SESSION_AUTO_LOGIN: Component
        get() = render("session.auto-login")
    val SESSION_INVALID: Component
        get() = render("session.invalid")
    val UNREGISTER_USAGE: Component
        get() = render("unregister.usage")
    val UNREGISTER_SUCCESS: Component
        get() = render("unregister.success")
    val UNREGISTER_FAILED: Component
        get() = render("unregister.failed")

    val EMAIL_USAGE: Component
        get() = render("email.usage")
    val EMAIL_DISABLED: Component
        get() = render("email.disabled")
    val EMAIL_INVALID: Component
        get() = render("email.invalid")
    val EMAIL_ADDED: Component
        get() = render("email.added")
    val EMAIL_CHANGED: Component
        get() = render("email.changed")
    val EMAIL_ALREADY_USED: Component
        get() = render("email.already-used")
    val EMAIL_NOT_SET: Component
        get() = render("email.not-set")
    val EMAIL_ADD_USAGE: Component
        get() = render("email.add-usage")
    val EMAIL_CHANGE_USAGE: Component
        get() = render("email.change-usage")
    val EMAIL_SHOW_USAGE: Component
        get() = render("email.show-usage")
    val EMAIL_RECOVERY_USAGE: Component
        get() = render("email.recovery-usage")
    val EMAIL_CODE_USAGE: Component
        get() = render("email.code-usage")
    val EMAIL_SETPASSWORD_USAGE: Component
        get() = render("email.setpassword-usage")
    val EMAIL_MISMATCH: Component
        get() = render("email.mismatch")
    val EMAIL_BIND_FAILED: Component
        get() = render("email.bind-failed")
    val EMAIL_OLD_MISMATCH: Component
        get() = render("email.old-mismatch")
    val EMAIL_CHANGE_FAILED: Component
        get() = render("email.change-failed")
    val RECOVERY_HINT: Component
        get() = render("email.recovery-hint")
    val RECOVERY_EMAIL_SENT: Component
        get() = render("email.recovery-email-sent")
    val RECOVERY_CODE_CORRECT: Component
        get() = render("email.recovery-code-correct")
    val RECOVERY_CODE_INCORRECT: Component
        get() = render("email.recovery-code-incorrect")
    val RECOVERY_CODE_EXPIRED: Component
        get() = render("email.recovery-code-expired")
    val RECOVERY_CODE_NOT_REQUESTED: Component
        get() = render("email.recovery-code-not-requested")
    val RECOVERY_PASSWORD_WINDOW_EXPIRED: Component
        get() = render("email.recovery-password-window-expired")
    val RECOVERY_CODE_WRITE_FAILED: Component
        get() = render("email.recovery-code-write-failed")
    val RECOVERY_STATE_UPDATE_FAILED: Component
        get() = render("email.recovery-state-update-failed")
    val OLD_PASSWORD_WRONG: Component
        get() = render("password.old-wrong")
    val PASSWORD_WRONG: Component
        get() = render("password.wrong")

    val TOTP_USAGE: Component
        get() = render("totp.usage")
    val TOTP_ADD_USAGE: Component
        get() = render("totp.add-usage")
    val TOTP_CONFIRM_USAGE: Component
        get() = render("totp.confirm-usage")
    val TOTP_REMOVE_USAGE: Component
        get() = render("totp.remove-usage")
    val TOTP_DISABLED_BY_CONFIG: Component
        get() = render("totp.disabled-by-config")
    val TOTP_ALREADY_ENABLED: Component
        get() = render("totp.already-enabled")
    val TOTP_NOT_ENABLED: Component
        get() = render("totp.not-enabled")
    val TOTP_PENDING_NOT_FOUND: Component
        get() = render("totp.pending-not-found")
    val TOTP_INVALID_CODE: Component
        get() = render("totp.invalid-code")
    val TOTP_ENABLED: Component
        get() = render("totp.enabled")
    val TOTP_DISABLED: Component
        get() = render("totp.disabled")
    val TOTP_ENABLE_FAILED: Component
        get() = render("totp.enable-failed")
    val TOTP_DISABLE_FAILED: Component
        get() = render("totp.disable-failed")
    val TOTP_LOGIN_REQUIRED: Component
        get() = render("totp.login-required")
    val TOTP_LOGIN_HINT: Component
        get() = render("totp.login-hint")
    val PENDING_BIND_PROMPT: Component
        get() = render("prompt.pending-bind")
    val LOGIN_OTHER_USERNAME_PROMPT: Component
        get() = render("prompt.login-other-username")
    val CHANGE_PASSWORD_PROMPT: Component
        get() = render("prompt.change-password")
    val EMAIL_ADD_PROMPT: Component
        get() = render("prompt.email-add")
    val TOTP_REMOVE_PROMPT: Component
        get() = render("prompt.totp-remove")
    val TOTP_ADD_PROMPT: Component
        get() = render("prompt.totp-add")

    fun loginBlocked(seconds: Long): Component {
        return render("login.blocked",
            HyperZoneMessagePlaceholder.text("duration", formatDuration(seconds))
        )
    }

    fun wrongPasswordWithRemainingAttempts(remainingAttempts: Int): Component {
        return render("login.wrong-password-with-remaining-attempts",
            HyperZoneMessagePlaceholder.text("remaining_attempts", remainingAttempts)
        )
    }

    fun loginAccountNotFound(username: String): Component {
        return render("login.account-not-found",
            HyperZoneMessagePlaceholder.text("username", username)
        )
    }

    fun loginCurrentNameNotRegistered(currentName: String): Component {
        return render("login.current-name-not-registered",
            HyperZoneMessagePlaceholder.text("current_name", currentName)
        )
    }

    fun unsafePassword(min: Int, max: Int): Component {
        return render("password.unsafe",
            HyperZoneMessagePlaceholder.text("min", min),
            HyperZoneMessagePlaceholder.text("max", max)
        )
    }

    fun passwordContainsName(name: String): Component {
        return render("password.contains-name",
            HyperZoneMessagePlaceholder.text("name", name)
        )
    }

    fun emailShow(email: String): Component {
        return render("email.show",
            HyperZoneMessagePlaceholder.text("email", email)
        )
    }

    fun recoveryCooldown(seconds: Long): Component {
        return render("email.recovery-cooldown",
            HyperZoneMessagePlaceholder.text("duration", formatDuration(seconds))
        )
    }

    fun recoveryCodeAttemptsExceeded(): Component {
        return render("email.recovery-code-attempts-exceeded")
    }

    fun recoverySendFailure(reason: String?): Component {
        return if (reason.isNullOrBlank()) {
            render("email.recovery-send-failure")
        } else {
            render("email.recovery-send-failure-with-reason",
                HyperZoneMessagePlaceholder.text("reason", reason)
            )
        }
    }

    fun recoveryEmailSentWithDiagnostic(diagnostic: String): Component {
        return render("email.recovery-email-sent-with-diagnostic",
            HyperZoneMessagePlaceholder.text("diagnostic", diagnostic)
        )
    }

    fun totpSetupGenerated(secret: String, otpAuthUrl: String): Component {
        return render("totp.setup-generated",
            HyperZoneMessagePlaceholder.text("secret", secret),
            HyperZoneMessagePlaceholder.text("otp_auth_url", otpAuthUrl)
        )
    }

    fun invalidRecoveryDeliveryMode(mode: String): Component {
        return render("email.invalid-recovery-delivery-mode",
            HyperZoneMessagePlaceholder.text("mode", mode)
        )
    }

    private fun render(key: String, vararg placeholders: HyperZoneMessagePlaceholder): Component {
        return HyperZoneMessageServiceProvider.get().render("$NAMESPACE.$key", *placeholders)
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

