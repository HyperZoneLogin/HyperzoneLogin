package icu.h2l.login.command

import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.proxy.Player
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.manager.HyperZonePlayerManager

class HyperZoneLoginCommand : SimpleCommand {
    override fun execute(invocation: SimpleCommand.Invocation) {
        val args = invocation.arguments()
        val sender = invocation.source()
        if (args.size == 0) {
            sender.sendPlainMessage("§e/hzl reload")
            return
        }
        if (args[0].equals("reload", ignoreCase = true)) {
            sender.sendPlainMessage("§aReloaded!")
            return
        } else if (args[0].equals("re", ignoreCase = true)) {
            if (sender !is Player) {
                sender.sendPlainMessage("§c该命令只能由玩家执行")
                return
            }

            sender.sendPlainMessage("§e开始重新认证...")
            HyperZoneLoginMain.getInstance().triggerLimboAuthForPlayer(sender)
            return
        } else if (args[0].equals("uuid", ignoreCase = true)) {
            if (sender !is Player) {
                sender.sendPlainMessage("§c该命令只能由玩家执行")
                return
            }

            val proxyPlayer = sender
            val hyperZonePlayer = HyperZonePlayerManager.getByPlayer(proxyPlayer)
            val profile = hyperZonePlayer.getDBProfile()

            sender.sendPlainMessage("§e[ProxyPlayer] name=${proxyPlayer.username} uuid=${proxyPlayer.uniqueId}")
            sender.sendPlainMessage("§e[HyperZonePlayer] verified=${hyperZonePlayer.isVerified()} canRegister=${hyperZonePlayer.canRegister()}")
            if (profile != null) {
                sender.sendPlainMessage("§e[Profile] id=${profile.id} name=${profile.name} uuid=${profile.uuid}")
            } else {
                sender.sendPlainMessage("§e[Profile] null")
            }
            return
        }
    }

    override fun hasPermission(invocation: SimpleCommand.Invocation): Boolean {
        return invocation.source().hasPermission("hyperzonelogin.admin")
    }
} 