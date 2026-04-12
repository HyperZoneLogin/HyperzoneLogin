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

package icu.h2l.login.profile.skin.service

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.util.GameProfile
import icu.h2l.api.event.profile.ProfileAttachedEvent
import icu.h2l.api.event.profile.ProfileSkinApplyEvent
import icu.h2l.api.event.profile.ProfileSkinPreprocessEvent
import icu.h2l.api.log.debug
import icu.h2l.api.log.error
import icu.h2l.api.log.warn
import icu.h2l.api.player.HyperZonePlayer
import icu.h2l.api.profile.HyperZoneProfileService
import icu.h2l.api.profile.skin.ProfileSkinModel
import icu.h2l.api.profile.skin.ProfileSkinSource
import icu.h2l.api.profile.skin.ProfileSkinTextures
import icu.h2l.login.profile.skin.ProfileSkinMessages
import icu.h2l.login.profile.skin.config.MineSkinMethod
import icu.h2l.login.profile.skin.config.ProfileSkinConfig
import icu.h2l.login.profile.skin.db.ProfileSkinCacheRepository
import icu.h2l.login.profile.skin.db.ProfileSkinCacheRepository.SaveResult
import icu.h2l.login.profile.skin.db.ProfileSkinProfileRepository
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

private class MineSkinRequestFailedException(
    val method: MineSkinMethod,
    val statusCode: Int,
    val body: String
) : IllegalStateException("MineSkin ${method.name} restore failed: HTTP $statusCode, body=$body")

internal fun shouldRetryUploadAfterUrlReadFailure(statusCode: Int, body: String): Boolean {
    if (statusCode != 400) {
        return false
    }

    val root = runCatching {
        JsonParser.parseString(body).asJsonObject
    }.getOrNull() ?: return body.contains("Invalid image file size: undefined", ignoreCase = true)

    val errorCode = root.getAsJsonPrimitive("errorCode")?.asString
    val error = root.getAsJsonPrimitive("error")?.asString
    return errorCode.equals("invalid_image", ignoreCase = true)
            && error?.contains("Invalid image file size: undefined", ignoreCase = true) == true
}

@Suppress("UNUSED_PARAMETER")
internal fun shouldUseSourceCache(shouldForceRestoreSignedTextures: Boolean): Boolean {
    /**
     * 现在 source cache 是否可复用由数据库中的 `source_cache_eligible` 明确控制，
     * 因此即使是“上游 signed 但不可信，需要强制修复”的场景，也应该先尝试命中同源已恢复缓存，
     * 避免对 MineSkin 重复请求并触发 429。
     */
    return true
}

@Suppress("UNUSED_PARAMETER")
internal fun sanitizeFallbackTextures(
    textures: ProfileSkinTextures,
    shouldForceRestoreSignedTextures: Boolean
): ProfileSkinTextures {
    /**
     * 当上游 signed textures 不可信且 MineSkin 修复失败时，不能再构造“value 存在但 signature 为空”的半残属性，
     * 因为 Velocity 的 `GameProfile.Property` 不接受空签名。
     *
     * 这里采用折中策略：保留最开始传入的整份 signed textures 作为 profile 级 fallback，
     * 同时配合 `sanitizeFallbackSourceHash` 禁止把这份不可信结果继续提升为 source 级缓存。
     */
    return textures
}

internal fun sanitizeFallbackSourceHash(
    sourceHash: String?,
    shouldForceRestoreSignedTextures: Boolean
): String? {
    return if (shouldForceRestoreSignedTextures) null else sourceHash
}

/**
 * MineSkin 成功响应中的签名约束参考并对齐自 SkinsRestorer：
 * - `ref/SkinsRestorer/shared/src/main/java/net/skinsrestorer/shared/connections/MineSkinAPIImpl.java`
 * - `ref/SkinsRestorer/shared/src/main/java/net/skinsrestorer/shared/connections/mineskin/responses/MineSkinUrlResponse.java`
 * - `ref/SkinsRestorer/api/src/main/java/net/skinsrestorer/api/property/SkinProperty.java`
 *
 * SkinsRestorer 在成功路径上会把 MineSkin 返回的 `value + signature` 直接组装成必填签名的 `SkinProperty`；
 * 这里也在恢复阶段立即拒绝缺签名响应，避免把不可注入的半残 textures 写入缓存。
 */
internal fun parseRestoredMineSkinTextures(body: String): ProfileSkinTextures {
    val root = JsonParser.parseString(body).asJsonObject
    if (root.getAsJsonPrimitive("success")?.asBoolean == false) {
        throw IllegalStateException("MineSkin response indicates failure: $body")
    }

    val texture = root.getAsJsonObject("data")
        ?.getAsJsonObject("texture")
        ?: root.getAsJsonObject("skin")
            ?.getAsJsonObject("texture")
            ?.getAsJsonObject("data")
        ?: throw IllegalStateException("MineSkin response missing signed texture payload: $body")

    val value = texture.getAsJsonPrimitive("value")?.asString?.takeIf { it.isNotBlank() }
        ?: throw IllegalStateException("MineSkin response missing value: $body")
    val signature = texture.getAsJsonPrimitive("signature")?.asString?.takeIf { it.isNotBlank() }
        ?: throw IllegalStateException("MineSkin response missing signature: $body")
    return ProfileSkinTextures(value = value, signature = signature)
}

class ProfileSkinService(
    private val config: ProfileSkinConfig,
    private val cacheRepository: ProfileSkinCacheRepository,
    private val profileRepository: ProfileSkinProfileRepository,
    private val profileService: HyperZoneProfileService
) {
    private val pendingSkinBindings = ConcurrentHashMap<HyperZonePlayer, UUID>()

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(config.mineSkin.timeoutMillis))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    @Subscribe
    fun onPreprocess(event: ProfileSkinPreprocessEvent) {
        if (!config.enabled) return

        val upstreamTextures = event.textures ?: extractTextures(event.authenticatedProfile)
        val source = (event.source ?: extractSkinSource(upstreamTextures))?.normalized()
        val sourceHash = source?.let(::sourceHash)
        val trustedSignedEntry = isTrustedSignedEntry(event.entryId)
        val trustedUpstreamTextures = upstreamTextures?.takeIf {
            it.isSigned && config.preferUpstreamSignedTextures && trustedSignedEntry
        }
        val shouldForceRestoreSignedTextures = upstreamTextures?.isSigned == true && !trustedSignedEntry
        val restoreSource = source?.takeIf {
            shouldForceRestoreSignedTextures || config.restoreUnsignedTextures
        }

        if (trustedUpstreamTextures != null) {
            persistPreprocessedSkin(
                event.hyperZonePlayer,
                cacheRepository.save(
                    source = source,
                    textures = trustedUpstreamTextures,
                    sourceHash = sourceHash,
                    sourceCacheEligible = true
                ),
                source,
                sourceHash,
                "trusted signed upstream"
            )
            event.textures = trustedUpstreamTextures
            return
        }

        if (upstreamTextures?.isSigned == true && !trustedSignedEntry) {
            debug {
                "[ProfileSkinFlow] preprocess signed upstream not trusted: clientOriginal=${event.hyperZonePlayer.clientOriginalName}, entry=${event.entryId}, source=${describeSource(source)}"
            }
        }

        if (restoreSource != null) {
            if (shouldUseSourceCache(shouldForceRestoreSignedTextures) && sourceHash != null) {
                cacheRepository.findBySourceHash(sourceHash)?.let { cached ->
                    rememberPendingSkin(event.hyperZonePlayer, cached.skinId, "source cache hit")
                    debug {
                        "[ProfileSkinFlow] source cache hit: skin=${cached.skinId}, clientOriginal=${event.hyperZonePlayer.clientOriginalName}, sourceHash=${shortHash(sourceHash)}, source=${describeSource(source)}"
                    }
                    event.textures = cached.textures
                    return
                }
            }

            runCatching {
                restoreTextures(restoreSource)
            }.onSuccess { restored ->
                persistPreprocessedSkin(
                    event.hyperZonePlayer,
                    cacheRepository.save(
                        source = source,
                        textures = restored,
                        sourceHash = sourceHash,
                        sourceCacheEligible = true
                    ),
                    source,
                    sourceHash,
                    "restored textures"
                )
                event.textures = restored
                return
            }.onFailure { throwable ->
                error(throwable) {
                    "Profile skin restore failed for clientOriginal=${event.hyperZonePlayer.clientOriginalName}, entry=${event.entryId}: ${throwable.message}"
                }
            }
        } else {
            debug {
                "[ProfileSkinFlow] preprocess restore skipped: clientOriginal=${event.hyperZonePlayer.clientOriginalName}, entry=${event.entryId}, source=${describeSource(source)}, reason=${describeRestoreSkipReason(source, shouldForceRestoreSignedTextures)}"
            }
        }

        if (upstreamTextures != null) {
            val fallbackTextures = sanitizeFallbackTextures(upstreamTextures, shouldForceRestoreSignedTextures)
            val fallbackSourceHash = sanitizeFallbackSourceHash(sourceHash, shouldForceRestoreSignedTextures)
            if (shouldForceRestoreSignedTextures && fallbackTextures.isSigned) {
                warn {
                    "[ProfileSkinFlow] preprocess fallback uses original untrusted signed textures after restore failure: clientOriginal=${event.hyperZonePlayer.clientOriginalName}, entry=${event.entryId}, source=${describeSource(source)}, sourceHashCacheDisabled=${fallbackSourceHash == null}"
                }
            }
            persistPreprocessedSkin(
                event.hyperZonePlayer,
                cacheRepository.save(
                    source = source,
                    textures = fallbackTextures,
                    sourceHash = fallbackSourceHash,
                    sourceCacheEligible = false
                ),
                source,
                fallbackSourceHash,
                "upstream fallback"
            )
            event.textures = fallbackTextures
        } else {
            debug {
                "[ProfileSkinFlow] preprocess finished without textures: clientOriginal=${event.hyperZonePlayer.clientOriginalName}, entry=${event.entryId}, source=${describeSource(source)}"
            }
        }
    }

    @Subscribe
    fun onProfileAttached(event: ProfileAttachedEvent) {
        if (!config.enabled) return

        val skinId = pendingSkinBindings.remove(event.hyperZonePlayer) ?: return
        if (profileRepository.bindProfile(event.profile.id, skinId)) {
            debug {
                "[ProfileSkinFlow] skin_profile linked: profile=${event.profile.id}, skin=$skinId, clientOriginal=${event.hyperZonePlayer.clientOriginalName}"
            }
            return
        }

        pendingSkinBindings[event.hyperZonePlayer] = skinId
        warn {
            "[ProfileSkinFlow] skin_profile link failed: profile=${event.profile.id}, skin=$skinId, clientOriginal=${event.hyperZonePlayer.clientOriginalName}"
        }
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        pendingSkinBindings.entries.removeIf { entry ->
            entry.key.getProxyPlayerOrNull() == event.player
        }
    }

    @Subscribe
    fun onApply(event: ProfileSkinApplyEvent) {
        if (!config.enabled) return

        val profileId = profileService.getAttachedProfile(event.hyperZonePlayer)?.id ?: run {
            debug {
                "[ProfileSkinFlow] apply listener failed: no attached DB profile, clientOriginal=${event.hyperZonePlayer.clientOriginalName}, base=${describeProfile(event.baseProfile)}"
            }
            event.hyperZonePlayer.sendMessage(ProfileSkinMessages.repairFailed(event.hyperZonePlayer))
            return
        }
        val skinId = profileRepository.findSkinIdByProfileId(profileId) ?: return
        cacheRepository.findBySkinId(skinId)?.let { cached ->
            event.textures = cached.textures
            return
        }
    }

    private fun restoreTextures(source: ProfileSkinSource): ProfileSkinTextures {
        val body = when (MineSkinMethod.from(config.mineSkin.method)) {
            MineSkinMethod.URL -> restoreByUrlWithUploadRetry(source)
            MineSkinMethod.UPLOAD -> restoreByUpload(source)
        }
        return parseRestoredMineSkinTextures(body)
    }

    private fun restoreByUrlWithUploadRetry(source: ProfileSkinSource): String {
        return runCatching {
            restoreByUrl(source)
        }.recoverCatching { throwable ->
            val failure = throwable as? MineSkinRequestFailedException
            if (failure?.method != MineSkinMethod.URL
                || !config.mineSkin.retryUploadOnUrlReadFailure
                || !shouldRetryUploadAfterUrlReadFailure(failure.statusCode, failure.body)
            ) {
                throw throwable
            }

            warn {
                "[ProfileSkinFlow] MineSkin URL restore hit remote-read failure, retrying with upload: source=${describeSource(source)}, status=${failure.statusCode}, errorBody=${failure.body}"
            }
            restoreByUpload(source)
        }.getOrThrow()
    }

    private fun restoreByUrl(source: ProfileSkinSource): String {
        val payload = JsonObject().apply {
            addProperty("name", UUID.randomUUID().toString().substring(0, 6))
            addProperty("variant", source.model)
            addProperty("visibility", 0)
            addProperty("url", source.skinUrl)
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create(config.mineSkin.urlEndpoint))
            .timeout(Duration.ofMillis(config.mineSkin.timeoutMillis))
            .header("User-Agent", config.mineSkin.userAgent)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw MineSkinRequestFailedException(MineSkinMethod.URL, response.statusCode(), response.body())
        }
        return response.body()
    }

    private fun restoreByUpload(source: ProfileSkinSource): String {
        val bytes = requireValidSkin(source.skinUrl)
        val boundary = "----HyperZoneLogin${UUID.randomUUID().toString().replace("-", "")}"
        val separator = "--$boundary\r\n"
        val end = "--$boundary--\r\n"
        val body = ByteArrayOutputStream().use { output ->
            output.write(separator.toByteArray(StandardCharsets.UTF_8))
            output.write("Content-Disposition: form-data; name=\"name\"\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
            output.write(UUID.randomUUID().toString().substring(0, 6).toByteArray(StandardCharsets.UTF_8))
            output.write("\r\n".toByteArray(StandardCharsets.UTF_8))

            output.write(separator.toByteArray(StandardCharsets.UTF_8))
            output.write("Content-Disposition: form-data; name=\"variant\"\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
            output.write(source.model.toByteArray(StandardCharsets.UTF_8))
            output.write("\r\n".toByteArray(StandardCharsets.UTF_8))

            output.write(separator.toByteArray(StandardCharsets.UTF_8))
            output.write("Content-Disposition: form-data; name=\"visibility\"\r\n\r\n0\r\n".toByteArray(StandardCharsets.UTF_8))

            output.write(separator.toByteArray(StandardCharsets.UTF_8))
            output.write("Content-Disposition: form-data; name=\"file\"; filename=\"upload.png\"\r\n".toByteArray(StandardCharsets.UTF_8))
            output.write("Content-Type: image/png\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
            output.write(bytes)
            output.write("\r\n".toByteArray(StandardCharsets.UTF_8))
            output.write(end.toByteArray(StandardCharsets.UTF_8))
            output.toByteArray()
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create(config.mineSkin.uploadEndpoint))
            .timeout(Duration.ofMillis(config.mineSkin.timeoutMillis))
            .header("User-Agent", config.mineSkin.userAgent)
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw MineSkinRequestFailedException(MineSkinMethod.UPLOAD, response.statusCode(), response.body())
        }
        return response.body()
    }


    private fun requireValidSkin(skinUrl: String): ByteArray {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(skinUrl))
            .timeout(Duration.ofMillis(config.mineSkin.timeoutMillis))
            .header("User-Agent", config.mineSkin.userAgent)
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Skin download failed: HTTP ${response.statusCode()}")
        }

        val bytes = response.body()
        ByteArrayInputStream(bytes).use { input ->
            val image: BufferedImage = ImageIO.read(input)
                ?: throw IllegalStateException("Skin image decode failed")
            if (image.width != 64) {
                throw IllegalStateException("Skin width is not 64")
            }
            if (image.height != 32 && image.height != 64) {
                throw IllegalStateException("Skin height is not 64 or 32")
            }
        }
        return bytes
    }

    private fun extractTextures(profile: GameProfile?): ProfileSkinTextures? {
        val property = profile?.properties?.firstOrNull { it.name.equals("textures", ignoreCase = true) } ?: return null
        return ProfileSkinTextures(property.value, property.signature)
    }

    private fun extractSkinSource(textures: ProfileSkinTextures?): ProfileSkinSource? {
        val value = textures?.value ?: return null
        val decoded = String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)
        val root = JsonParser.parseString(decoded).asJsonObject
        val skin = root.getAsJsonObject("textures")
            ?.getAsJsonObject("SKIN")
            ?: return null
        val url = skin.getAsJsonPrimitive("url")?.asString ?: return null
        val model = skin.getAsJsonObject("metadata")
            ?.getAsJsonPrimitive("model")
            ?.asString
        return ProfileSkinSource(url, ProfileSkinModel.normalize(model))
    }

    private fun sourceHash(source: ProfileSkinSource): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val value = "${source.skinUrl}|${source.model}".toByteArray(StandardCharsets.UTF_8)
        return digest.digest(value).joinToString("") { "%02x".format(it) }
    }

    private fun describeProfile(profile: GameProfile?): String {
        if (profile == null) {
            return "null"
        }
        return "id=${profile.id}, name=${profile.name}, properties=${profile.properties.size}, textures=${describeTextures(extractTextures(profile))}"
    }

    private fun describeTextures(textures: ProfileSkinTextures?): String {
        if (textures == null) {
            return "none"
        }
        return "present(valueLength=${textures.value.length}, signed=${textures.isSigned})"
    }

    private fun describeSource(source: ProfileSkinSource?): String {
        if (source == null) {
            return "none"
        }
        return "url=${source.skinUrl}, model=${source.model}"
    }

    private fun shortHash(value: String?): String {
        if (value.isNullOrBlank()) {
            return "none"
        }
        return value.take(12)
    }

    private fun isTrustedSignedEntry(entryId: String): Boolean {
        return config.trustedSignedTextureEntries.any { it.equals(entryId, ignoreCase = true) }
    }

    private fun describeRestoreSkipReason(
        source: ProfileSkinSource?,
        shouldForceRestoreSignedTextures: Boolean
    ): String {
        if (source == null) {
            return if (shouldForceRestoreSignedTextures) {
                "missing source for untrusted signed textures"
            } else {
                "missing source"
            }
        }
        return if (shouldForceRestoreSignedTextures) {
            "untrusted signed textures without restore path"
        } else {
            "restoreUnsignedTextures disabled"
        }
    }

    private fun persistPreprocessedSkin(
        hyperZonePlayer: HyperZonePlayer,
        result: SaveResult,
        source: ProfileSkinSource?,
        sourceHash: String?,
        reason: String
    ) {
        rememberPendingSkin(hyperZonePlayer, result.record.skinId, reason)
        logSaveResult(result, source, sourceHash, reason)
    }

    private fun rememberPendingSkin(
        hyperZonePlayer: HyperZonePlayer,
        skinId: UUID,
        reason: String
    ) {
        pendingSkinBindings[hyperZonePlayer] = skinId
        val profileId = profileService.getAttachedProfile(hyperZonePlayer)?.id ?: return
        if (profileRepository.bindProfile(profileId, skinId)) {
            pendingSkinBindings.remove(hyperZonePlayer, skinId)
            debug {
                "[ProfileSkinFlow] immediate skin_profile link: profile=$profileId, skin=$skinId, clientOriginal=${hyperZonePlayer.clientOriginalName}, reason=$reason"
            }
        } else {
            warn {
                "[ProfileSkinFlow] immediate skin_profile link failed: profile=$profileId, skin=$skinId, clientOriginal=${hyperZonePlayer.clientOriginalName}, reason=$reason"
            }
        }
    }

    private fun logSaveResult(
        result: SaveResult,
        source: ProfileSkinSource?,
        sourceHash: String?,
        reason: String
    ) {
        debug {
            "[ProfileSkinFlow] skin cache ${result.action.name.lowercase()}: skin=${result.record.skinId}, reason=$reason, sourceHash=${shortHash(sourceHash)}, reusable=${result.record.sourceCacheEligible}, source=${describeSource(source)}"
        }
    }
}
