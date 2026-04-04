package icu.h2l.login.vServer.limbo.command

import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.proxy.Player
import icu.h2l.login.manager.HyperZonePlayerManager
import icu.h2l.login.player.OpenVcHyperZonePlayer
import net.kyori.adventure.text.Component

class ExitLimboCommand : SimpleCommand {
    override fun execute(invocation: SimpleCommand.Invocation) {
        val source = invocation.source()
        if (source !is Player) {
            source.sendPlainMessage("§c该命令只能由玩家执行")
            return
        }

        val hyperZonePlayer = HyperZonePlayerManager.getByPlayer(source)
        if (!hyperZonePlayer.isVerified()) {
            source.sendPlainMessage("§c尚未完成认证，无法退出")
            return
        }

        (hyperZonePlayer as OpenVcHyperZonePlayer).exitLimbo()
        source.sendMessage(Component.text("§a已尝试退出认证服务器"))
    }

    override fun hasPermission(invocation: SimpleCommand.Invocation): Boolean {
        return true
    }
}