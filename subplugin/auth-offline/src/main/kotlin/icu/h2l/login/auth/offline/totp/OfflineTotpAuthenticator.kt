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

package icu.h2l.login.auth.offline.totp

import com.warrenstrange.googleauth.GoogleAuthenticator
import com.warrenstrange.googleauth.GoogleAuthenticatorKey
import com.warrenstrange.googleauth.GoogleAuthenticatorQRGenerator
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class OfflineTotpAuthenticator(
    private val issuer: String,
    private val pendingExpireMinutes: Int
) {
    data class PendingSetup(
        val profileId: UUID,
        val playerName: String,
        val secret: String,
        val otpAuthUrl: String,
        val expireAt: Long
    )

    private val authenticator = GoogleAuthenticator()
    private val pendingSetups = ConcurrentHashMap<UUID, PendingSetup>()
    private val usedCodes = ConcurrentHashMap<String, Long>()

    fun createSetup(profileId: UUID, playerName: String): PendingSetup {
        cleanupExpiredState()
        val credentials: GoogleAuthenticatorKey = authenticator.createCredentials()
        val otpAuthUrl = GoogleAuthenticatorQRGenerator.getOtpAuthURL(issuer, playerName, credentials)
        val setup = PendingSetup(
            profileId = profileId,
            playerName = playerName,
            secret = credentials.key,
            otpAuthUrl = otpAuthUrl,
            expireAt = System.currentTimeMillis() + pendingExpireMinutes.coerceAtLeast(1) * 60_000L
        )
        pendingSetups[profileId] = setup
        return setup
    }

    fun getPendingSetup(profileId: UUID): PendingSetup? {
        cleanupExpiredState()
        val setup = pendingSetups[profileId] ?: return null
        return setup.takeIf { it.expireAt > System.currentTimeMillis() }
    }

    fun clearPendingSetup(profileId: UUID) {
        pendingSetups.remove(profileId)
    }

    fun verifyPendingCode(profileId: UUID, playerName: String, code: String): Boolean {
        val setup = getPendingSetup(profileId) ?: return false
        return verifyCode(playerName, setup.secret, code)
    }

    fun verifyCode(playerName: String, secret: String, code: String): Boolean {
        cleanupExpiredState()
        val numericCode = code.trim().toIntOrNull() ?: return false
        val playerKey = playerName.lowercase(Locale.ROOT)
        val codeKey = "$playerKey:$numericCode"
        if (usedCodes.containsKey(codeKey)) {
            return false
        }
        if (!authenticator.authorize(secret, numericCode)) {
            return false
        }
        usedCodes[codeKey] = System.currentTimeMillis()
        return true
    }

    private fun cleanupExpiredState() {
        val now = System.currentTimeMillis()
        pendingSetups.entries.removeIf { (_, value) -> value.expireAt <= now }
        val usedCodeThreshold = now - USED_CODE_RETENTION_MILLIS
        usedCodes.entries.removeIf { (_, value) -> value < usedCodeThreshold }
    }

    companion object {
        private const val USED_CODE_RETENTION_MILLIS = 5 * 60_000L
    }
}

