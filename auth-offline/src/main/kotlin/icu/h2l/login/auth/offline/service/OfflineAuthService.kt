package icu.h2l.login.auth.offline.service

import com.velocitypowered.api.proxy.Player
import icu.h2l.api.player.HyperZonePlayerAccessor
import icu.h2l.login.auth.offline.api.db.OfflineAuthEntry
import icu.h2l.login.auth.offline.db.OfflineAuthRepository
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class OfflineAuthService(
    private val repository: OfflineAuthRepository,
    private val playerAccessor: HyperZonePlayerAccessor
) {
    data class Result(val success: Boolean, val message: String)

    fun register(player: Player, password: String): Result {
        val hyperZonePlayer = playerAccessor.getByPlayer(player)
        val username = hyperZonePlayer.userName
        val normalizedName = username.lowercase()
        if (!hyperZonePlayer.canRegister()) {
            return Result(false, "§c其他渠道已注册，如有需要，请进行绑定")
        }

        if (repository.getByName(normalizedName) != null) {
            return Result(false, "§c你已经注册过，无法重复注册")
        }

        val profile = hyperZonePlayer.register()
        val hash = hashPassword(password)
        val created = repository.create(
            name = normalizedName,
            passwordHash = hash,
            hashFormat = HASH_FORMAT_SHA256,
            profileId = profile.id
        )
        return if (created) {
            hyperZonePlayer.overVerify()
            Result(true, "§a注册成功，已自动登录")
        } else {
            Result(false, "§c注册失败，请稍后再试")
        }
    }

    fun bind(player: Player, password: String): Result {
        val hyperZonePlayer = playerAccessor.getByPlayer(player)
        val username = hyperZonePlayer.userName
        val normalizedName = username.lowercase()
        if (!hyperZonePlayer.canBind()) {
            return Result(false, "§c尚未完成验证，无法绑定")
        }

        val profile = hyperZonePlayer.getDBProfile() ?: return Result(false, "§c未找到档案，无法绑定")
        if (repository.getByProfileId(profile.id) != null || repository.getByName(normalizedName) != null) {
            return Result(false, "§c已绑定，无需重复绑定")
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

    fun login(player: Player, password: String): Result {
        val username = playerAccessor.getByPlayer(player).userName
        val entry = repository.getByName(username.lowercase()) ?: return Result(false, "§c尚未注册")
        if (!verifyPassword(password, entry)) {
            return Result(false, "§c密码错误")
        }

        val hyperZonePlayer = playerAccessor.getByPlayer(player)
        hyperZonePlayer.overVerify()
        return Result(true, "§a登录成功，已通过验证")
    }

    fun changePassword(player: Player, oldPassword: String, newPassword: String): Result {
        val username = playerAccessor.getByPlayer(player).userName
        val entry = repository.getByName(username.lowercase()) ?: return Result(false, "§c尚未注册")
        if (!verifyPassword(oldPassword, entry)) {
            return Result(false, "§c旧密码错误")
        }

        val updated = repository.updatePassword(
            profileId = entry.profileId,
            passwordHash = hashPassword(newPassword),
            hashFormat = HASH_FORMAT_SHA256
        )
        return if (updated) {
            Result(true, "§a密码已更新")
        } else {
            Result(false, "§c密码更新失败，请稍后再试")
        }
    }

    fun unregister(player: Player, password: String): Result {
        val username = playerAccessor.getByPlayer(player).userName
        val entry = repository.getByName(username.lowercase()) ?: return Result(false, "§c尚未注册")
        if (!verifyPassword(password, entry)) {
            return Result(false, "§c密码错误")
        }

        val deleted = repository.deleteByProfileId(entry.profileId)
        return if (deleted) {
            Result(true, "§a账号已注销")
        } else {
            Result(false, "§c注销失败，请稍后再试")
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

    companion object {
        private const val HASH_FORMAT_PLAIN = "plain"
        private const val HASH_FORMAT_SHA256 = "sha256"
        private const val HASH_FORMAT_AUTHME = "authme"
    }
}