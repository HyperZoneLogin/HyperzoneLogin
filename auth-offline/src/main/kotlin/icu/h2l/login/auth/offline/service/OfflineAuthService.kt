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

package icu.h2l.login.auth.offline.service

import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.Player
import icu.h2l.api.event.auth.AuthenticationFailureEvent
import icu.h2l.api.player.HyperZonePlayerAccessor
import icu.h2l.login.auth.offline.OfflineAuthMessages
import icu.h2l.login.auth.offline.config.OfflineAuthConfigLoader
import icu.h2l.login.auth.offline.api.db.OfflineAuthEntry
import icu.h2l.login.auth.offline.db.OfflineAuthRepository
import icu.h2l.login.auth.offline.mail.OfflineAuthEmailSender
import icu.h2l.login.auth.offline.totp.OfflineTotpAuthenticator
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

class OfflineAuthService(
    private val repository: OfflineAuthRepository,
    private val playerAccessor: HyperZonePlayerAccessor,
    private val emailSender: OfflineAuthEmailSender,
    private val totpAuthenticator: OfflineTotpAuthenticator,
    private val proxy: ProxyServer
) {
    data class Result(val success: Boolean, val message: String)
    data class SessionCheckResult(val passed: Boolean, val message: String? = null)

    private val logger = java.util.logging.Logger.getLogger("hzl-auth-offline")
    private val secureRandom = SecureRandom()

    fun register(player: Player, password: String): Result {
        val hyperZonePlayer = playerAccessor.getByPlayer(player)
        val username = hyperZonePlayer.userName
        val normalizedName = username.lowercase()
        if (!hyperZonePlayer.canResolveOrCreateProfile()) {
            return Result(false, "§c其他渠道已注册，如有需要，请进行绑定")
        }

        if (repository.getByName(normalizedName) != null) {
            return Result(false, OfflineAuthMessages.REGISTER_REPEAT)
        }

        validatePassword(username, password)?.let {
            return it
        }

        val profile = hyperZonePlayer.resolveOrCreateProfile()
        val hash = hashPassword(password)
        val created = repository.create(
            name = normalizedName,
            passwordHash = hash,
            hashFormat = HASH_FORMAT_SHA256,
            profileId = profile.id
        )
        return if (created) {
            hyperZonePlayer.overVerify()
            if (OfflineAuthConfigLoader.getConfig().session.enabled && OfflineAuthConfigLoader.getConfig().session.issueOnRegister) {
                issueSession(profile.id, player)
            }
            Result(true, OfflineAuthMessages.REGISTER_SUCCESS)
        } else {
            Result(false, OfflineAuthMessages.REGISTER_FAILED)
        }
    }

    fun bind(player: Player, password: String): Result {
        val hyperZonePlayer = playerAccessor.getByPlayer(player)
        val username = hyperZonePlayer.userName
        val normalizedName = username.lowercase()
        if (!hyperZonePlayer.canBind()) {
            return Result(false, OfflineAuthMessages.DENIED_COMMAND)
        }

        val profile = hyperZonePlayer.getDBProfile() ?: return Result(false, "§c未找到档案，无法绑定")
        if (repository.getByProfileId(profile.id) != null || repository.getByName(normalizedName) != null) {
            return Result(false, "§c已绑定，无需重复绑定")
        }

        validatePassword(username, password)?.let {
            return it
        }

        val created = repository.create(
            name = normalizedName,
            passwordHash = hashPassword(password),
            hashFormat = HASH_FORMAT_SHA256,
            profileId = profile.id
        )
        return if (created) {
            Result(true, "§a绑定成功")
        } else {
            Result(false, "§c绑定失败，请稍后再试")
        }
    }

    fun login(player: Player, password: String, totpCode: String? = null): Result {
        val hyperPlayer = playerAccessor.getByPlayer(player)
        if (!hyperPlayer.isInWaitingArea()) {
            return Result(false, OfflineAuthMessages.ALREADY_LOGGED_IN)
        }

        val entry = resolveEntryByPlayer(player) ?: return Result(false, "§c尚未注册")

        val now = System.currentTimeMillis()
        val blockedUntil = entry.loginBlockedUntil
        if (blockedUntil != null && blockedUntil > now) {
            publishAuthFailure(
                player = player,
                authType = AuthenticationFailureEvent.AuthType.OFFLINE,
                reason = AuthenticationFailureEvent.Reason.RATE_LIMITED,
                reasonMessage = "offline login temporarily blocked"
            )
            return Result(false, OfflineAuthMessages.loginBlocked(((blockedUntil - now) / 1000).coerceAtLeast(1)))
        }

        if (!verifyPassword(password, entry)) {
            val protection = OfflineAuthConfigLoader.getConfig().login
            val nextFailCount = entry.loginFailCount + 1
            if (nextFailCount >= protection.maxAttempts) {
                val blockedTo = now + protection.blockSeconds * 1000L
                repository.updateLoginProtection(entry.profileId, 0, blockedTo)
                publishAuthFailure(
                    player = player,
                    authType = AuthenticationFailureEvent.AuthType.OFFLINE,
                    reason = AuthenticationFailureEvent.Reason.RATE_LIMITED,
                    reasonMessage = "offline login blocked after too many failures"
                )
                return Result(false, OfflineAuthMessages.loginBlocked(protection.blockSeconds.toLong()))
            }

            repository.updateLoginProtection(entry.profileId, nextFailCount, null)
            val remainingAttempts = (protection.maxAttempts - nextFailCount).coerceAtLeast(0)
            publishAuthFailure(
                player = player,
                authType = AuthenticationFailureEvent.AuthType.OFFLINE,
                reason = AuthenticationFailureEvent.Reason.INVALID_CREDENTIALS,
                reasonMessage = "offline password mismatch"
            )
            return Result(false, OfflineAuthMessages.wrongPasswordWithRemainingAttempts(remainingAttempts))
        }

        repository.resetLoginProtection(entry.profileId)

        if (isTotpEnabled(entry)) {
            val trimmedCode = totpCode?.trim().orEmpty()
            if (trimmedCode.isEmpty()) {
                publishAuthFailure(
                    player = player,
                    authType = AuthenticationFailureEvent.AuthType.OFFLINE,
                    reason = AuthenticationFailureEvent.Reason.TOTP_REQUIRED,
                    reasonMessage = "totp code required for offline login"
                )
                return Result(false, OfflineAuthMessages.TOTP_LOGIN_REQUIRED)
            }
            if (!totpAuthenticator.verifyCode(entry.name, entry.totpSecret!!, trimmedCode)) {
                publishAuthFailure(
                    player = player,
                    authType = AuthenticationFailureEvent.AuthType.OFFLINE,
                    reason = AuthenticationFailureEvent.Reason.TOTP_INVALID,
                    reasonMessage = "invalid offline totp code"
                )
                return Result(false, OfflineAuthMessages.TOTP_INVALID_CODE)
            }
        }

        ensureAttachedProfile(player, entry)?.let { return it }
        hyperPlayer.overVerify()
        issueSession(entry.profileId, player)
        return Result(true, OfflineAuthMessages.LOGIN_SUCCESS)
    }

    fun beginTotpSetup(player: Player, password: String): Result {
        ensureTotpFeatureEnabled()?.let { return it }

        val entry = resolveEntryByPlayer(player) ?: return Result(false, OfflineAuthMessages.UNREGISTERED)
        if (!verifyPassword(password, entry)) {
            return Result(false, OfflineAuthMessages.PASSWORD_WRONG)
        }
        if (isTotpEnabled(entry)) {
            return Result(false, OfflineAuthMessages.TOTP_ALREADY_ENABLED)
        }

        val setup = totpAuthenticator.createSetup(entry.profileId, entry.name)
        return Result(true, OfflineAuthMessages.totpSetupGenerated(setup.secret, setup.otpAuthUrl))
    }

    fun confirmTotpSetup(player: Player, code: String): Result {
        ensureTotpFeatureEnabled()?.let { return it }

        val entry = resolveEntryByPlayer(player) ?: return Result(false, OfflineAuthMessages.UNREGISTERED)
        if (isTotpEnabled(entry)) {
            return Result(false, OfflineAuthMessages.TOTP_ALREADY_ENABLED)
        }

        val pendingSetup = totpAuthenticator.getPendingSetup(entry.profileId)
            ?: return Result(false, OfflineAuthMessages.TOTP_PENDING_NOT_FOUND)
        if (!totpAuthenticator.verifyPendingCode(entry.profileId, entry.name, code)) {
            return Result(false, OfflineAuthMessages.TOTP_INVALID_CODE)
        }

        return if (repository.updateTotpSecret(entry.profileId, pendingSetup.secret)) {
            totpAuthenticator.clearPendingSetup(entry.profileId)
            repository.clearSession(entry.profileId)
            Result(true, OfflineAuthMessages.TOTP_ENABLED)
        } else {
            Result(false, "§c二步验证启用失败，请稍后再试")
        }
    }

    fun disableTotp(player: Player, password: String, code: String): Result {
        ensureTotpFeatureEnabled()?.let { return it }

        val entry = resolveEntryByPlayer(player) ?: return Result(false, OfflineAuthMessages.UNREGISTERED)
        if (!verifyPassword(password, entry)) {
            return Result(false, OfflineAuthMessages.PASSWORD_WRONG)
        }
        val secret = entry.totpSecret ?: return Result(false, OfflineAuthMessages.TOTP_NOT_ENABLED)
        if (!totpAuthenticator.verifyCode(entry.name, secret, code)) {
            return Result(false, OfflineAuthMessages.TOTP_INVALID_CODE)
        }

        return if (repository.updateTotpSecret(entry.profileId, null)) {
            totpAuthenticator.clearPendingSetup(entry.profileId)
            Result(true, OfflineAuthMessages.TOTP_DISABLED)
        } else {
            Result(false, "§c二步验证关闭失败，请稍后再试")
        }
    }

    fun changePassword(player: Player, oldPassword: String, newPassword: String): Result {
        val entry = resolveEntryByPlayer(player) ?: return Result(false, "§c尚未注册")
        if (!verifyPassword(oldPassword, entry)) {
            return Result(false, OfflineAuthMessages.OLD_PASSWORD_WRONG)
        }

        val username = playerAccessor.getByPlayer(player).userName

        validatePassword(username, newPassword)?.let {
            return it
        }

        val updated = repository.updatePassword(
            profileId = entry.profileId,
            passwordHash = hashPassword(newPassword),
            hashFormat = HASH_FORMAT_SHA256
        )
        return if (updated) {
            Result(true, OfflineAuthMessages.PASSWORD_CHANGED)
        } else {
            Result(false, "§c密码更新失败，请稍后再试")
        }
    }

    fun logout(player: Player): Result {
        val hyperPlayer = playerAccessor.getByPlayer(player)
        if (hyperPlayer.isInWaitingArea()) {
            return Result(false, OfflineAuthMessages.NOT_LOGGED_IN)
        }

        resolveEntryByPlayer(player)?.let { repository.clearSession(it.profileId) }
        hyperPlayer.resetVerify()
        return Result(true, OfflineAuthMessages.LOGOUT_SUCCESS)
    }

    fun unregister(player: Player, password: String): Result {
        val entry = resolveEntryByPlayer(player) ?: return Result(false, "§c尚未注册")
        if (!verifyPassword(password, entry)) {
            return Result(false, OfflineAuthMessages.PASSWORD_WRONG)
        }

        val deleted = repository.deleteByProfileId(entry.profileId)
        return if (deleted) {
            Result(true, OfflineAuthMessages.UNREGISTER_SUCCESS)
        } else {
            Result(false, "§c注销失败，请稍后再试")
        }
    }

    fun addEmail(player: Player, password: String, email: String, confirmEmail: String): Result {
        ensureEmailFeatureEnabled()?.let { return it }

        if (!email.equals(confirmEmail, ignoreCase = true)) {
            return Result(false, "§c两次输入的邮箱不一致")
        }

        val normalizedEmail = normalizeEmail(email) ?: return Result(false, OfflineAuthMessages.EMAIL_INVALID)
        val entry = resolveEntryByPlayer(player) ?: return Result(false, OfflineAuthMessages.UNREGISTERED)
        if (!verifyPassword(password, entry)) {
            return Result(false, OfflineAuthMessages.PASSWORD_WRONG)
        }

        val occupied = repository.getByEmail(normalizedEmail)
        if (occupied != null && occupied.profileId != entry.profileId) {
            return Result(false, OfflineAuthMessages.EMAIL_ALREADY_USED)
        }

        return if (repository.updateEmail(entry.profileId, normalizedEmail)) {
            Result(true, OfflineAuthMessages.EMAIL_ADDED)
        } else {
            Result(false, "§c邮箱绑定失败，请稍后再试")
        }
    }

    fun changeEmail(player: Player, password: String, oldEmail: String, newEmail: String): Result {
        ensureEmailFeatureEnabled()?.let { return it }

        val normalizedOldEmail = normalizeEmail(oldEmail) ?: return Result(false, OfflineAuthMessages.EMAIL_INVALID)
        val normalizedNewEmail = normalizeEmail(newEmail) ?: return Result(false, OfflineAuthMessages.EMAIL_INVALID)
        val entry = resolveEntryByPlayer(player) ?: return Result(false, OfflineAuthMessages.UNREGISTERED)
        if (!verifyPassword(password, entry)) {
            return Result(false, OfflineAuthMessages.PASSWORD_WRONG)
        }

        if (!normalizedOldEmail.equals(entry.email, ignoreCase = true)) {
            return Result(false, "§c旧邮箱不匹配")
        }

        val occupied = repository.getByEmail(normalizedNewEmail)
        if (occupied != null && occupied.profileId != entry.profileId) {
            return Result(false, OfflineAuthMessages.EMAIL_ALREADY_USED)
        }

        return if (repository.updateEmail(entry.profileId, normalizedNewEmail)) {
            Result(true, OfflineAuthMessages.EMAIL_CHANGED)
        } else {
            Result(false, "§c邮箱修改失败，请稍后再试")
        }
    }

    fun showEmail(player: Player, password: String): Result {
        ensureEmailFeatureEnabled()?.let { return it }

        val entry = resolveEntryByPlayer(player) ?: return Result(false, OfflineAuthMessages.UNREGISTERED)
        if (!verifyPassword(password, entry)) {
            return Result(false, OfflineAuthMessages.PASSWORD_WRONG)
        }

        return if (entry.email.isNullOrBlank()) {
            Result(false, OfflineAuthMessages.EMAIL_NOT_SET)
        } else {
            Result(true, OfflineAuthMessages.emailShow(entry.email))
        }
    }

    fun startEmailRecovery(player: Player, email: String): Result {
        ensureEmailFeatureEnabled()?.let { return it }

        val normalizedEmail = normalizeEmail(email) ?: return Result(false, OfflineAuthMessages.EMAIL_INVALID)
        val entry = repository.getByEmail(normalizedEmail) ?: return Result(false, OfflineAuthMessages.EMAIL_NOT_SET)
        val now = System.currentTimeMillis()
        val emailConfig = OfflineAuthConfigLoader.getConfig().email
        val cooldownMillis = emailConfig.recoveryCooldownSeconds * 1000L

        if (entry.recoveryRequestedAt != null && now - entry.recoveryRequestedAt < cooldownMillis) {
            val remaining = ((cooldownMillis - (now - entry.recoveryRequestedAt)) / 1000).coerceAtLeast(1)
            return Result(false, OfflineAuthMessages.recoveryCooldown(remaining))
        }

        val code = generateRecoveryCode(emailConfig.recoveryCodeLength)
        val expireAt = now + emailConfig.recoveryCodeExpireMinutes * 60_000L
        if (!repository.startRecovery(entry.profileId, hashPassword(code), expireAt, now)) {
            return Result(false, "§c写入恢复码失败，请稍后再试")
        }

        val deliveryResult = deliverRecoveryCode(player.username, normalizedEmail, code, expireAt)
        if (!deliveryResult.success) {
            repository.clearRecoveryState(entry.profileId)
            return Result(false, OfflineAuthMessages.recoverySendFailure(deliveryResult.diagnosticMessage))
        }

        val successMessage = if (!deliveryResult.diagnosticMessage.isNullOrBlank() &&
            !deliveryResult.diagnosticMessage.equals("SMTP", ignoreCase = true)
        ) {
            "${OfflineAuthMessages.RECOVERY_EMAIL_SENT} §7(${deliveryResult.diagnosticMessage})"
        } else {
            OfflineAuthMessages.RECOVERY_EMAIL_SENT
        }
        return Result(true, successMessage)
    }

    fun verifyRecoveryCode(player: Player, code: String): Result {
        ensureEmailFeatureEnabled()?.let { return it }

        val entry = resolveEntryByPlayer(player) ?: return Result(false, OfflineAuthMessages.UNREGISTERED)
        val storedHash = entry.recoveryCodeHash ?: return Result(false, OfflineAuthMessages.RECOVERY_CODE_NOT_REQUESTED)
        val now = System.currentTimeMillis()

        if (entry.recoveryCodeExpireAt == null || entry.recoveryCodeExpireAt < now) {
            repository.clearRecoveryState(entry.profileId)
            return Result(false, OfflineAuthMessages.RECOVERY_CODE_EXPIRED)
        }

        val emailConfig = OfflineAuthConfigLoader.getConfig().email
        if (entry.recoveryVerifyTries >= emailConfig.maxCodeVerifyAttempts) {
            repository.clearRecoveryState(entry.profileId)
            return Result(false, OfflineAuthMessages.recoveryCodeAttemptsExceeded())
        }

        if (hashPassword(code) != storedHash) {
            repository.incrementRecoveryVerifyTries(entry.profileId)
            return Result(false, OfflineAuthMessages.RECOVERY_CODE_INCORRECT)
        }

        val verifiedUntil = now + emailConfig.resetPasswordWindowMinutes * 60_000L
        return if (repository.markRecoveryVerified(entry.profileId, verifiedUntil)) {
            Result(true, OfflineAuthMessages.RECOVERY_CODE_CORRECT)
        } else {
            Result(false, "§c恢复码状态更新失败，请稍后再试")
        }
    }

    fun setPasswordByRecovery(player: Player, newPassword: String): Result {
        ensureEmailFeatureEnabled()?.let { return it }

        val hyperPlayer = playerAccessor.getByPlayer(player)
        val username = hyperPlayer.userName
        val entry = resolveEntryByPlayer(player) ?: return Result(false, OfflineAuthMessages.UNREGISTERED)
        val verifiedUntil = entry.resetPasswordVerifiedUntil
        val now = System.currentTimeMillis()
        if (verifiedUntil == null || verifiedUntil < now) {
            repository.clearRecoveryState(entry.profileId)
            return Result(false, OfflineAuthMessages.RECOVERY_PASSWORD_WINDOW_EXPIRED)
        }

        validatePassword(username, newPassword)?.let {
            return it
        }

        val updated = repository.updatePassword(entry.profileId, hashPassword(newPassword), HASH_FORMAT_SHA256)
        if (!updated) {
            return Result(false, "§c密码重置失败，请稍后再试")
        }

        ensureAttachedProfile(player, entry)?.let { return it }
        hyperPlayer.overVerify()
        return Result(true, "${OfflineAuthMessages.PASSWORD_CHANGED} §7已自动通过本次认证")
    }

    fun getJoinPrompts(player: Player): List<String> {
        val hyperPlayer = playerAccessor.getByPlayer(player)
        if (!hyperPlayer.isInWaitingArea()) {
            return emptyList()
        }

        val entry = resolveEntryByPlayer(player)
        val prompts = ArrayList<String>()

        if (entry == null) {
            prompts += OfflineAuthMessages.REGISTER_REQUEST
            if (!hyperPlayer.canResolveOrCreateProfile()) {
                prompts += "§8[§6玩家系统§8] §e检测到你已有档案，可使用 /bind <密码> <再次输入密码> 绑定离线密码"
            }
            return prompts
        }

        prompts += OfflineAuthMessages.LOGIN_REQUEST
        if (isTotpEnabled(entry)) {
            prompts += OfflineAuthMessages.TOTP_LOGIN_HINT
        }
        prompts += "§8[§6玩家系统§8] §7如需修改密码：/changepassword <旧密码> <新密码>"
        if (OfflineAuthConfigLoader.getConfig().prompt.showRecoveryHint && !entry.email.isNullOrBlank()) {
            prompts += OfflineAuthMessages.RECOVERY_HINT
        }
        if (OfflineAuthConfigLoader.getConfig().email.enabled) {
            prompts += if (entry.email.isNullOrBlank()) {
                "§8[§6玩家系统§8] §7可使用 /email add <当前密码> <邮箱> <再次输入邮箱> 绑定邮箱"
            } else {
                OfflineAuthMessages.emailShow(entry.email)
            }
        }
        if (OfflineAuthConfigLoader.getConfig().totp.enabled) {
            prompts += if (isTotpEnabled(entry)) {
                "§8[§6玩家系统§8] §7已启用 TOTP，可使用 /totp remove <密码> <验证码> 关闭"
            } else {
                "§8[§6玩家系统§8] §7可使用 /totp add <密码> 启用二步验证"
            }
        }
        return prompts
    }

    fun tryAutoLogin(player: Player): SessionCheckResult? {
        val sessionConfig = OfflineAuthConfigLoader.getConfig().session
        if (!sessionConfig.enabled) {
            return null
        }

        val hyperPlayer = playerAccessor.getByPlayer(player)
        if (!hyperPlayer.isInWaitingArea()) {
            return SessionCheckResult(true)
        }

        val entry = resolveEntryByPlayer(player) ?: return null
        if (isTotpEnabled(entry) && !OfflineAuthConfigLoader.getConfig().totp.allowSessionBypass) {
            repository.clearSession(entry.profileId)
            publishAuthFailure(
                player = player,
                authType = AuthenticationFailureEvent.AuthType.OFFLINE,
                reason = AuthenticationFailureEvent.Reason.SESSION_REJECTED,
                reasonMessage = "session bypass rejected because totp is enabled"
            )
            return SessionCheckResult(false, OfflineAuthMessages.TOTP_LOGIN_REQUIRED)
        }
        val sessionIssuedAt = entry.sessionIssuedAt ?: return null
        val sessionExpiresAt = entry.sessionExpiresAt ?: return null
        val currentIp = getPlayerRemoteAddress(player)
        val now = System.currentTimeMillis()

        val invalidByTime = sessionExpiresAt <= now || sessionIssuedAt > sessionExpiresAt
        val invalidByIp = sessionConfig.bindIp && !entry.sessionIp.isNullOrBlank() && entry.sessionIp != currentIp
        if (invalidByTime || invalidByIp) {
            repository.clearSession(entry.profileId)
            publishAuthFailure(
                player = player,
                authType = AuthenticationFailureEvent.AuthType.OFFLINE,
                reason = AuthenticationFailureEvent.Reason.SESSION_REJECTED,
                reasonMessage = if (invalidByIp) "session ip mismatch" else "session expired"
            )
            return SessionCheckResult(false, OfflineAuthMessages.SESSION_INVALID)
        }

        ensureAttachedProfile(player, entry)?.let { failed ->
            return SessionCheckResult(false, failed.message)
        }
        hyperPlayer.overVerify()
        return SessionCheckResult(true, OfflineAuthMessages.SESSION_AUTO_LOGIN)
    }

    private fun ensureAttachedProfile(player: Player, entry: OfflineAuthEntry): Result? {
        val hyperPlayer = playerAccessor.getByPlayer(player)
        val attached = hyperPlayer.attachProfile(entry.profileId)
        return if (attached != null) {
            null
        } else {
            Result(false, "§c未找到已绑定的游戏档案，无法完成本次认证")
        }
    }

    private fun verifyPassword(password: String, entry: OfflineAuthEntry): Boolean {
        return when (entry.hashFormat.lowercase()) {
            HASH_FORMAT_PLAIN -> password == entry.passwordHash
            HASH_FORMAT_SHA256 -> hashPassword(password) == entry.passwordHash
            HASH_FORMAT_AUTHME -> verifyAuthMe(password, entry.passwordHash)
            else -> hashPassword(password) == entry.passwordHash
        }
    }

    private fun hashPassword(password: String): String {
        return sha256Hex(password)
    }

    private fun verifyAuthMe(password: String, storedHash: String): Boolean {
        val parts = storedHash.split("$")
        if (parts.size != 4) {
            return false
        }

        val salt = parts[2]
        val expected = parts[3]
        val first = hashPassword(password)
        val computed = sha256Hex(first + salt)
        return computed.equals(expected, ignoreCase = true)
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun validatePassword(username: String, password: String): Result? {
        val policy = OfflineAuthConfigLoader.getConfig().password
        if (password.length !in policy.minLength..policy.maxLength) {
            return Result(false, OfflineAuthMessages.unsafePassword(policy.minLength, policy.maxLength))
        }

        if (policy.denyNameInPassword && password.lowercase(Locale.ROOT).contains(username.lowercase(Locale.ROOT))) {
            return Result(false, OfflineAuthMessages.passwordContainsName(username))
        }

        return null
    }

    private fun resolveEntryByPlayer(player: Player, allowNameFallback: Boolean = true): OfflineAuthEntry? {
        val hyperPlayer = playerAccessor.getByPlayer(player)
        val profileId = hyperPlayer.getDBProfile()?.id
        if (profileId != null) {
            repository.getByProfileId(profileId)?.let { return it }
        }

        if (!allowNameFallback) {
            return null
        }

        return repository.getByName(hyperPlayer.userName.lowercase())
    }

    private fun normalizeEmail(email: String): String? {
        val candidate = email.trim().lowercase(Locale.ROOT)
        if (!EMAIL_PATTERN.matches(candidate)) {
            return null
        }
        return candidate
    }

    private fun ensureEmailFeatureEnabled(): Result? {
        if (!OfflineAuthConfigLoader.getConfig().email.enabled) {
            return Result(false, OfflineAuthMessages.EMAIL_DISABLED)
        }
        return null
    }

    private fun ensureTotpFeatureEnabled(): Result? {
        if (!OfflineAuthConfigLoader.getConfig().totp.enabled) {
            return Result(false, OfflineAuthMessages.TOTP_DISABLED_BY_CONFIG)
        }
        return null
    }

    private fun generateRecoveryCode(length: Int): String {
        val resolvedLength = length.coerceAtLeast(4)
        return buildString(resolvedLength) {
            repeat(resolvedLength) {
                append(RECOVERY_CODE_CHARS[secureRandom.nextInt(RECOVERY_CODE_CHARS.length)])
            }
        }
    }

    private fun deliverRecoveryCode(
        playerName: String,
        email: String,
        code: String,
        expireAt: Long
    ): OfflineAuthEmailSender.DeliveryResult {
        val expireMinutes = ((expireAt - System.currentTimeMillis()) / 60_000L).coerceAtLeast(1)
        val mailMessage = OfflineAuthEmailSender.RecoveryCodeMailMessage(
            playerName = playerName,
            email = email,
            recoveryCode = code,
            expireMinutes = expireMinutes
        )
        val result = emailSender.sendRecoveryCode(mailMessage)
        if (!result.success) {
            logger.warning("离线找回邮件发送失败: player=$playerName email=$email cause=${result.diagnosticMessage}")
        }
        return result
    }

    private fun issueSession(profileId: java.util.UUID, player: Player) {
        val sessionConfig = OfflineAuthConfigLoader.getConfig().session
        val entry = repository.getByProfileId(profileId)
        if (entry != null && isTotpEnabled(entry) && !OfflineAuthConfigLoader.getConfig().totp.allowSessionBypass) {
            repository.clearSession(profileId)
            return
        }
        if (!sessionConfig.enabled) {
            repository.clearSession(profileId)
            return
        }

        val now = System.currentTimeMillis()
        val sessionIp = if (sessionConfig.bindIp) getPlayerRemoteAddress(player) else null
        val expiresAt = now + sessionConfig.expireMinutes.coerceAtLeast(1) * 60_000L
        repository.issueSession(profileId, sessionIp, now, expiresAt)
    }

    private fun getPlayerRemoteAddress(player: Player): String {
        val hostAddress = player.remoteAddress.address.hostAddress
        val ipv6ScopeIdx = hostAddress.indexOf('%')
        return if (ipv6ScopeIdx == -1) {
            hostAddress
        } else {
            hostAddress.substring(0, ipv6ScopeIdx)
        }
    }

    private fun publishAuthFailure(
        player: Player,
        authType: AuthenticationFailureEvent.AuthType,
        reason: AuthenticationFailureEvent.Reason,
        reasonMessage: String,
        providerId: String? = null,
        throwableSummary: String? = null
    ) {
        proxy.eventManager.fire(
            AuthenticationFailureEvent(
                userName = player.username,
                playerIp = getPlayerRemoteAddress(player),
                authType = authType,
                reason = reason,
                reasonMessage = reasonMessage,
                providerId = providerId,
                throwableSummary = throwableSummary
            )
        )
    }

    private fun isTotpEnabled(entry: OfflineAuthEntry): Boolean {
        return !entry.totpSecret.isNullOrBlank()
    }

    companion object {
        private const val HASH_FORMAT_PLAIN = "plain"
        private const val HASH_FORMAT_SHA256 = "sha256"
        private const val HASH_FORMAT_AUTHME = "authme"
        private val EMAIL_PATTERN = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$")
        private const val RECOVERY_CODE_CHARS = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    }
}