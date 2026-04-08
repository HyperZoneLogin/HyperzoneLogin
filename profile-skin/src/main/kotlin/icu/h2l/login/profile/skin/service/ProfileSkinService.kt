package icu.h2l.login.profile.skin.service

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.util.GameProfile
import icu.h2l.api.event.profile.ProfileSkinApplyEvent
import icu.h2l.api.event.profile.ProfileSkinPreprocessEvent
import icu.h2l.api.log.debug
import icu.h2l.api.log.error
import icu.h2l.api.profile.skin.ProfileSkinModel
import icu.h2l.api.profile.skin.ProfileSkinSource
import icu.h2l.api.profile.skin.ProfileSkinTextures
import icu.h2l.login.profile.skin.config.MineSkinMethod
import icu.h2l.login.profile.skin.config.ProfileSkinConfig
import icu.h2l.login.profile.skin.db.ProfileSkinCacheRepository
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO

class ProfileSkinService(
    private val config: ProfileSkinConfig,
    private val repository: ProfileSkinCacheRepository
) {
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(config.mineSkin.timeoutMillis))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    @Subscribe
    fun onPreprocess(event: ProfileSkinPreprocessEvent) {
        if (!config.enabled) return

        val profileId = event.hyperZonePlayer.getDBProfile()?.id ?: return
        val upstreamTextures = event.textures ?: extractTextures(event.authenticatedProfile)
        val source = (event.source ?: extractSkinSource(upstreamTextures))?.normalized()
        val sourceHash = source?.let(::sourceHash)

        if (upstreamTextures != null && upstreamTextures.isSigned && config.preferUpstreamSignedTextures) {
            repository.save(profileId, source, upstreamTextures, sourceHash)
            event.textures = upstreamTextures
            return
        }

        if (source != null && config.restoreUnsignedTextures) {
            repository.findBySourceHash(sourceHash!!)?.let { cached ->
                repository.save(profileId, source, cached.textures, sourceHash)
                event.textures = cached.textures
                return
            }

            runCatching {
                restoreTextures(source)
            }.onSuccess { restored ->
                repository.save(profileId, source, restored, sourceHash)
                event.textures = restored
                debug { "Profile skin restored for profile=$profileId source=${source.skinUrl}" }
                return
            }.onFailure { throwable ->
                error(throwable) { "Profile skin restore failed for profile=$profileId: ${throwable.message}" }
            }
        }

        if (upstreamTextures != null) {
            repository.save(profileId, source, upstreamTextures, sourceHash)
            event.textures = upstreamTextures
        }
    }

    @Subscribe
    fun onApply(event: ProfileSkinApplyEvent) {
        if (!config.enabled) return

        val profileId = event.hyperZonePlayer.getDBProfile()?.id ?: return
        repository.findByProfileId(profileId)?.let { cached ->
            event.textures = cached.textures
            return
        }

        if (!config.allowInitialProfileFallback) {
            return
        }

        val fallbackTextures = extractTextures(event.hyperZonePlayer.getInitialGameProfile())
            ?: extractTextures(event.baseProfile)
        if (fallbackTextures != null) {
            event.textures = fallbackTextures
        }
    }

    private fun restoreTextures(source: ProfileSkinSource): ProfileSkinTextures {
        val body = when (MineSkinMethod.from(config.mineSkin.method)) {
            MineSkinMethod.URL -> restoreByUrl(source)
            MineSkinMethod.UPLOAD -> restoreByUpload(source)
        }
        return parseMineSkinResponse(body)
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
            throw IllegalStateException("MineSkin URL restore failed: HTTP ${response.statusCode()}, body=${response.body()}")
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
            throw IllegalStateException("MineSkin upload restore failed: HTTP ${response.statusCode()}, body=${response.body()}")
        }
        return response.body()
    }

    private fun parseMineSkinResponse(body: String): ProfileSkinTextures {
        val root = JsonParser.parseString(body).asJsonObject
        val texture = root.getAsJsonObject("data")
            ?.getAsJsonObject("texture")
            ?: throw IllegalStateException("MineSkin response missing data.texture: $body")

        val value = texture.getAsJsonPrimitive("value")?.asString
            ?: throw IllegalStateException("MineSkin response missing value: $body")
        val signature = texture.getAsJsonPrimitive("signature")?.asString
        return ProfileSkinTextures(value = value, signature = signature)
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
}


