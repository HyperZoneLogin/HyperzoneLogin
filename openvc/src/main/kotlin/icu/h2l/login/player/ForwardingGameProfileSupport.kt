package icu.h2l.login.player

import com.velocitypowered.api.util.GameProfile
import icu.h2l.api.player.HyperZonePlayer

object ForwardingGameProfileSupport {
    fun resolveBaseProfile(hyperZonePlayer: HyperZonePlayer, preferTemporaryProfile: Boolean): GameProfile {
        if (preferTemporaryProfile) {
            hyperZonePlayer.getTemporaryForwardingProfile()?.let { temporaryProfile ->
                return temporaryProfile
            }
        }
        return hyperZonePlayer.getGameProfile()
    }

    fun resolveBaseProfileSource(hyperZonePlayer: HyperZonePlayer, preferTemporaryProfile: Boolean): String {
        return if (preferTemporaryProfile && hyperZonePlayer.getTemporaryForwardingProfile() != null) {
            "temporaryForwardingProfile"
        } else {
            "playerGameProfile"
        }
    }

    fun resolveProfile(hyperZonePlayer: HyperZonePlayer, preferTemporaryProfile: Boolean): GameProfile {
        val baseProfile = resolveBaseProfile(hyperZonePlayer, preferTemporaryProfile)
        return ProfileSkinApplySupport.apply(hyperZonePlayer, baseProfile)
    }
}

