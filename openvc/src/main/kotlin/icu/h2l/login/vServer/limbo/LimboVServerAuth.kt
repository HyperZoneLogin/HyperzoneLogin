package icu.h2l.login.vServer.limbo

import com.velocitypowered.api.command.CommandMeta
import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.plugin.PluginContainer
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import icu.h2l.api.event.vServer.VServerAuthStartEvent
import icu.h2l.api.vServer.HyperZoneVServerAdapter
import icu.h2l.login.vServer.limbo.handler.LimboAuthSessionHandler
import icu.h2l.login.manager.HyperZonePlayerManager
import icu.h2l.login.HyperZoneLoginMain
import net.elytrium.limboapi.api.Limbo
import net.elytrium.limboapi.api.LimboFactory
import net.elytrium.limboapi.api.chunk.Dimension
import net.elytrium.limboapi.api.chunk.VirtualWorld
import net.elytrium.limboapi.api.event.LoginLimboRegisterEvent
import net.elytrium.limboapi.api.player.GameMode

/**
 * Adapter over the real Limbo API. This class bridges the third-party Limbo API
 * to our internal adapter interface. Only construct this when Limbo is present.
 */
class LimboVServerAuth(private val server: ProxyServer) : HyperZoneVServerAdapter {
    private val factory: LimboFactory
    private lateinit var limboAuthServer: Limbo

    init {
        factory = server.pluginManager.getPlugin("limboapi")
            .flatMap { obj: PluginContainer -> obj.instance }
            .orElseThrow() as LimboFactory
    }

    fun load() {
        val authWorld: VirtualWorld = factory.createVirtualWorld(
            Dimension.OVERWORLD,
            0.0, 0.0, 0.0,
            0f, 0f
        )

        limboAuthServer = factory
            .createLimbo(authWorld)
            .setName("HyperzoneLogin")
            .setWorldTime(1000L)
            .setGameMode(GameMode.ADVENTURE)
    }

    @Subscribe
    fun onLoginLimboRegister(event: LoginLimboRegisterEvent) {
        event.addOnJoinCallback { authPlayer(event.player) }
    }

    fun authPlayer(player: Player) {
        val hyperZonePlayer = HyperZonePlayerManager.getByPlayer(player)

        val VServerAuthStartEvent = VServerAuthStartEvent(player, hyperZonePlayer)
        HyperZoneLoginMain.getInstance().proxy.eventManager.fire(VServerAuthStartEvent).join()
        if (VServerAuthStartEvent.pass) {
            factory.passLoginLimbo(player)
            return
        }

        val newHandler = LimboAuthSessionHandler(player, hyperZonePlayer)
        limboAuthServer.spawnPlayer(player, newHandler)
    }

    // HyperZoneLimboAdapter implementation --------------------------------------------------
    override fun registerCommand(meta: CommandMeta, command: SimpleCommand) {
        limboAuthServer.registerCommand(meta, command)
    }
}

