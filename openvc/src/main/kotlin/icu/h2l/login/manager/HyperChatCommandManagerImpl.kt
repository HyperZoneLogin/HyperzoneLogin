package icu.h2l.login.manager

import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import icu.h2l.api.command.HyperChatCommandManager
import icu.h2l.api.command.HyperChatCommandRegistration
import icu.h2l.api.vServer.HyperZoneVServerAdapter
import icu.h2l.login.player.OpenVcHyperZonePlayer
import net.kyori.adventure.text.Component
import java.util.concurrent.ConcurrentHashMap

object HyperChatCommandManagerImpl : HyperChatCommandManager {
    private val commands = ConcurrentHashMap<String, HyperChatCommandRegistration>()
    @Volatile
    private var limboAdapter: HyperZoneVServerAdapter? = null
    @Volatile
    private var proxyServer: ProxyServer? = null
    @Volatile
    private var proxyFallbackCommandsEnabled: Boolean = false
    private val proxyRegisteredCommands = ConcurrentHashMap.newKeySet<String>()

    fun bindLimbo(proxy: ProxyServer, adapter: HyperZoneVServerAdapter?) {
        proxyServer = proxy
        limboAdapter = adapter
        getRegisteredCommands().forEach { registerToLimbo(it) }
        getRegisteredCommands().forEach { registerToProxyFallback(it) }
    }

    fun setProxyFallbackCommandsEnabled(enabled: Boolean) {
        proxyFallbackCommandsEnabled = enabled
        if (enabled) {
            getRegisteredCommands().forEach { registerToProxyFallback(it) }
        }
    }

    override fun register(registration: HyperChatCommandRegistration) {
        commands[registration.name.lowercase()] = registration
        registration.aliases.forEach { alias ->
            commands[alias.lowercase()] = registration
        }
        registerToLimbo(registration)
        registerToProxyFallback(registration)
    }

    override fun unregister(name: String) {
        val registration = commands[name.lowercase()] ?: return
        commands.entries.removeIf { (_, value) -> value === registration }
    }

    override fun executeChat(source: CommandSource, chat: String): Boolean {
        val input = chat.trim()
        if (!input.startsWith("/")) return false

        val body = input.substring(1).trim()
        if (body.isEmpty()) return false

        val parts = body.split(Regex("\\s+"))
        val label = parts.first().lowercase()
        val args = if (parts.size > 1) parts.drop(1).toTypedArray() else emptyArray()

        val registration = commands[label] ?: return false
        val invocation = ChatInvocation(source, label, args)
        if (!registration.command.hasPermission(invocation)) {
            source.sendMessage(Component.text("§c没有权限"))
            return true
        }

        registration.command.execute(invocation)
        return true
    }

    override fun getRegisteredCommands(): Collection<HyperChatCommandRegistration> {
        return commands.values.toSet()
    }

    private fun registerToLimbo(registration: HyperChatCommandRegistration) {
        val proxy = proxyServer ?: return
        val authServer = limboAdapter ?: return

        val metaBuilder = proxy.commandManager.metaBuilder(registration.name)
        if (registration.aliases.isNotEmpty()) {
            metaBuilder.aliases(*registration.aliases.toTypedArray())
        }

        authServer.registerCommand(metaBuilder.build(), registration.command)
    }

    private fun registerToProxyFallback(registration: HyperChatCommandRegistration) {
        if (!proxyFallbackCommandsEnabled) return

        val proxy = proxyServer ?: return
        val canonicalName = registration.name.lowercase()
        if (!proxyRegisteredCommands.add(canonicalName)) {
            return
        }

        val metaBuilder = proxy.commandManager.metaBuilder(registration.name)
        if (registration.aliases.isNotEmpty()) {
            metaBuilder.aliases(*registration.aliases.toTypedArray())
        }

        proxy.commandManager.register(metaBuilder.build(), object : SimpleCommand {
            override fun execute(invocation: SimpleCommand.Invocation) {
                val source = invocation.source()
                if (source !is Player) {
                    source.sendMessage(Component.text("§c该命令只能由玩家执行"))
                    return
                }

                val hyperPlayer = runCatching {
                    HyperZonePlayerManager.getByPlayer(source) as OpenVcHyperZonePlayer
                }.getOrNull()

                if (hyperPlayer == null || !hyperPlayer.isInBackendAuthHold()) {
                    source.sendMessage(Component.text("§e该命令仅可在认证等待阶段使用"))
                    return
                }

                registration.command.execute(invocation)
            }

            override fun hasPermission(invocation: SimpleCommand.Invocation): Boolean {
                return registration.command.hasPermission(invocation)
            }
        })
    }

    private data class ChatInvocation(
        private val source: CommandSource,
        private val alias: String,
        private val args: Array<String>
    ) : SimpleCommand.Invocation {
        override fun source(): CommandSource = source
        override fun arguments(): Array<String> = args
        override fun alias(): String = alias
    }
}
