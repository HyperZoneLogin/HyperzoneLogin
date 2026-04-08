package icu.h2l.login.config

import org.spongepowered.configurate.objectmapping.ConfigSerializable
import org.spongepowered.configurate.objectmapping.meta.Comment

@ConfigSerializable
class MiscConfig {
    @Comment("开启 debug 模式")
    val debug: Boolean = true

    @Comment("是否启用替换 GameProfile")
    val enableReplaceGameProfile: Boolean = true

    @Comment("不给服务器发送 CHAT_SESSION_UPDATE包")
    val killChatSession: Boolean = true

    @Comment("当未安装 limboapi 时，使用真实后端服务器作为认证等待区；留空表示禁用")
    val fallbackAuthServer: String = "lobby"

    @Comment("在真实服务器认证等待区内，如果玩家尝试前往其他服务器，是否记住目标并在认证成功后自动连接")
    val rememberRequestedServerDuringAuth: Boolean = true
}

