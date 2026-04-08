package icu.h2l.login.player

import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.util.GameProfile
import icu.h2l.api.db.Profile
import icu.h2l.api.player.HyperZonePlayer
import icu.h2l.api.util.RemapUtils
import icu.h2l.login.HyperZoneLoginMain
import net.elytrium.limboapi.api.player.LimboPlayer
import net.kyori.adventure.text.Component
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class OpenVcHyperZonePlayer(
//    最开始客户端传入的，不可信
    override var userName: String,
    override var uuid: UUID,
) : HyperZonePlayer {

    private var proxyPlayer: Player? = null

    @Volatile
    var profileId: UUID? = null

    private val isVerifiedState = AtomicBoolean(false)
    private val hasSpawned = AtomicBoolean(false)
    private val messageQueue = ConcurrentLinkedQueue<Component>()
    private val authJoinAnnounced = AtomicBoolean(false)
    private val authHoldServerName = AtomicReference<String?>(null)
    private val postAuthTargetServerName = AtomicReference<String?>(null)
    private val onlineAuthName = AtomicReference<String?>(null)
    private val onlineAuthUuid = AtomicReference<UUID?>(null)

    @Volatile
    private var limboPlayer: LimboPlayer? = null

    @Volatile
    private var initialGameProfile: GameProfile? = null

    private val databaseHelper = HyperZoneLoginMain.getInstance().databaseHelper

    init {
        profileId = databaseHelper
            .getProfileByNameOrUuid(userName, uuid)
            ?.id
        profileId?.let { _ ->
            val profile = getDBProfile()
            userName = profile!!.name
            uuid = profile.uuid
        }
    }

    fun update(player: Player) {
        proxyPlayer = player
        if (hasSpawned.compareAndSet(false, true)) {
            while (messageQueue.isNotEmpty()) {
                val message = messageQueue.poll() ?: continue
                proxyPlayer?.sendMessage(message)
            }
        }
    }

    fun onSpawn(player: LimboPlayer) {
        limboPlayer = player
        update(player.proxyPlayer)
        hasSpawned.set(true)

        while (messageQueue.isNotEmpty()) {
            val message = messageQueue.poll() ?: continue
            proxyPlayer?.sendMessage(message)
        }
    }

    override fun canRegister(): Boolean {
        return profileId == null
    }

    override fun register(userName: String?, uuid: UUID?): Profile {
        val resolvedName = userName ?: this.userName
        val remapPrefix = HyperZoneLoginMain.getRemapConfig().prefix
        val resolvedUuid = uuid ?: RemapUtils.genUUID(resolvedName, remapPrefix)

        val existing = databaseHelper.getProfileByNameOrUuid(resolvedName, resolvedUuid)
        if (existing != null) {
            throw IllegalStateException("玩家 $resolvedName 已存在 Profile，无法重复注册")
        }

        val profile = Profile(
            id = RemapUtils.genProfileUUID(resolvedName),
            name = resolvedName,
            uuid = resolvedUuid
        )

        val created = databaseHelper.createProfile(profile.id, profile.name, profile.uuid)
        if (!created) {
            throw IllegalStateException("玩家 ${userName} 注册失败，数据库写入失败")
        }

        profileId = profile.id

        return profile
    }

    override fun getDBProfile(): Profile? {
        val currentProfileId = profileId ?: return null
        return databaseHelper.getProfile(currentProfileId)
    }

    override fun isVerified(): Boolean {
        return isVerifiedState.get()
    }

    override fun canBind(): Boolean {
        return isVerified()
    }

    override fun overVerify() {
        if (isVerifiedState.compareAndSet(false, true)) {
            val player = proxyPlayer
            val authServer = authHoldServerName.getAndSet(null)
            val targetServer = postAuthTargetServerName.getAndSet(null)
            authJoinAnnounced.set(false)
            limboPlayer?.disconnect()

            if (player != null && !targetServer.isNullOrBlank() && !targetServer.equals(authServer, ignoreCase = true)) {
                val target = HyperZoneLoginMain.getInstance().proxy.getServer(targetServer).orElse(null)
                if (target != null) {
                    player.createConnectionRequest(target).connect().whenComplete { result, throwable ->
                        if (throwable != null) {
                            player.sendPlainMessage("§c认证完成后自动连接到目标服务器失败：${throwable.message ?: "未知错误"}")
                            return@whenComplete
                        }

                        if (result == null || !result.isSuccessful) {
                            val reason = result?.reasonComponent?.map { component ->
                                component.toString()
                            }?.orElse("未知原因") ?: "未知原因"
                            player.sendPlainMessage("§c认证完成，但自动连接到目标服务器失败：$reason")
                        }
                    }
                } else {
                    player.sendPlainMessage("§c认证完成，但目标服务器 $targetServer 不存在")
                }
            }
        }
    }

    fun exitLimbo() {
        limboPlayer?.disconnect()
    }

    override fun sendMessage(message: Component) {
        if (hasSpawned.get()) {
            proxyPlayer?.sendMessage(message)
            return
        }

        messageQueue.offer(message)
    }

    override fun getGameProfile(): GameProfile {
        if (!HyperZoneLoginMain.getMiscConfig().enableReplaceGameProfile) {
//            不开就可以从玩家获取
            return proxyPlayer!!.gameProfile
        }
        val resolvedProfile = getDBProfile()
        val fallbackName = onlineAuthName.get()
            ?: initialGameProfile?.name
            ?: proxyPlayer?.gameProfile?.name
            ?: userName
        val fallbackUuid = uuid
        val resolvedProperties = ArrayList(
            initialGameProfile?.properties
                ?: proxyPlayer?.gameProfile?.properties
                ?: emptyList()
        )
        return GameProfile(
            resolvedProfile?.uuid ?: fallbackUuid,
            resolvedProfile?.name ?: fallbackName,
            resolvedProperties
        )
    }

    override fun getInitialGameProfile(): GameProfile? {
        return initialGameProfile
    }

    override fun setInitialGameProfile(profile: GameProfile?) {
        initialGameProfile = profile
    }

    fun recordOnlineAuthIdentity(name: String, uuid: UUID) {
        onlineAuthName.set(name)
        onlineAuthUuid.set(uuid)
    }

    fun getOnlineAuthName(): String? {
        return onlineAuthName.get()
    }

    fun getOnlineAuthUuid(): UUID? {
        return onlineAuthUuid.get()
    }

    fun beginBackendAuthHold(authServerName: String, targetServerName: String?) {
        authHoldServerName.set(authServerName)
        postAuthTargetServerName.set(targetServerName?.takeUnless { it.isBlank() })
        authJoinAnnounced.set(false)
    }

    fun isInBackendAuthHold(): Boolean {
        return !isVerified() && !authHoldServerName.get().isNullOrBlank()
    }

    fun getBackendAuthHoldServerName(): String? {
        return authHoldServerName.get()
    }

    fun getPostAuthTargetServerName(): String? {
        return postAuthTargetServerName.get()
    }

    fun rememberPostAuthTarget(serverName: String?) {
        val resolved = serverName?.takeUnless { it.isBlank() } ?: return
        postAuthTargetServerName.set(resolved)
    }

    fun markBackendAuthJoinHandled(serverName: String): Boolean {
        val holdServer = authHoldServerName.get() ?: return false
        if (!holdServer.equals(serverName, ignoreCase = true)) {
            return false
        }
        return authJoinAnnounced.compareAndSet(false, true)
    }

    fun clearBackendAuthHold() {
        authHoldServerName.set(null)
        postAuthTargetServerName.set(null)
        authJoinAnnounced.set(false)
    }
}
