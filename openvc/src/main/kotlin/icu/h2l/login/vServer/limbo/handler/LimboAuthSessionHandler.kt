package icu.h2l.login.vServer.limbo.handler

import com.velocitypowered.api.proxy.Player
import icu.h2l.api.event.vServer.VServerJoinEvent
import icu.h2l.api.player.HyperZonePlayer
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.player.OpenVcHyperZonePlayer
import net.elytrium.limboapi.api.Limbo
import net.elytrium.limboapi.api.LimboSessionHandler
import net.elytrium.limboapi.api.player.LimboPlayer

class LimboAuthSessionHandler(
    private val proxyPlayer: Player,
    private val hyperZonePlayer: HyperZonePlayer
) : LimboSessionHandler {

    override fun onSpawn(server: Limbo, player: LimboPlayer) {
        (hyperZonePlayer as OpenVcHyperZonePlayer).onSpawn(player)
        player.disableFalling()

        HyperZoneLoginMain.getInstance().proxy.eventManager.fire(
            VServerJoinEvent(proxyPlayer, hyperZonePlayer)
        )
    }

    override fun onChat(chat: String?) {
        val input = chat ?: return
        HyperZoneLoginMain.getInstance().chatCommandManager.executeChat(proxyPlayer, input)
    }
}
