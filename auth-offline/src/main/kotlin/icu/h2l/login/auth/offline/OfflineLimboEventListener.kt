package icu.h2l.login.auth.offline

import com.velocitypowered.api.event.Subscribe
import icu.h2l.api.event.vServer.VServerJoinEvent
import net.kyori.adventure.text.Component

class OfflineLimboEventListener {
    @Subscribe
    fun onLimboSpawn(event: VServerJoinEvent) {
        if (event.proxyPlayer.isOnlineMode) return

        event.hyperZonePlayer.sendMessage(Component.text("§e[HyperZoneLogin] 可用离线命令："))
        event.hyperZonePlayer.sendMessage(Component.text("§a/login <password> §7- 使用密码登录"))
        event.hyperZonePlayer.sendMessage(Component.text("§a/register <password> <password> §7- 注册新账户"))
        event.hyperZonePlayer.sendMessage(Component.text("§a/bind <password> <password> §7- 绑定已存在档案"))
        event.hyperZonePlayer.sendMessage(Component.text("§a/changepassword <oldPassword> <newPassword> §7- 修改账户密码"))
        event.hyperZonePlayer.sendMessage(Component.text("§a/logout §7- 退出当前登录状态"))
        event.hyperZonePlayer.sendMessage(Component.text("§a/unregister <password> §7- 注销当前账户"))
    }
}
