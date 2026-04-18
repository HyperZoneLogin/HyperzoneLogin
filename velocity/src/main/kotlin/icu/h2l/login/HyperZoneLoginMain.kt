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

package icu.h2l.login

import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.proxy.ProxyServer
import icu.h2l.api.HyperZoneApi
import icu.h2l.api.command.HyperChatCommandManager
import icu.h2l.api.command.HyperChatCommandRegistration
import icu.h2l.api.message.HyperZoneMessageServiceProvider
import icu.h2l.api.profile.HyperZoneProfileServiceProvider
import icu.h2l.api.vServer.HyperZoneVServerAdapter
import icu.h2l.api.module.HyperSubModule
import icu.h2l.api.player.HyperZonePlayerAccessor
// Module implementations (auth-offline, auth-yggd, data-merge) are now separate plugins
// and will register themselves with the main plugin at runtime. Do not import them here.
import icu.h2l.login.command.BindCodeCommandRegistrar
import icu.h2l.login.command.HyperZoneLoginCommand
import icu.h2l.login.command.RenameCommand
import icu.h2l.login.command.ReUuidCommand
import icu.h2l.login.database.BindingCodeRepository
import icu.h2l.login.config.BackendServerConfig
import icu.h2l.login.config.DatabaseSourceConfig
import icu.h2l.login.config.DebugConfig
import icu.h2l.login.config.MessagesConfig
import icu.h2l.login.config.MiscConfig
import icu.h2l.login.config.ModulesConfig
import icu.h2l.login.config.OutPreConfig
import icu.h2l.login.config.RemapConfig
import icu.h2l.login.database.DatabaseConfig
import icu.h2l.login.database.DatabaseHelper
import icu.h2l.login.inject.network.VelocityNetworkModule
import icu.h2l.login.vServer.backend.BackendAuthHoldListener
import icu.h2l.login.vServer.outpre.OutPreVServerAuth
import icu.h2l.login.vServer.command.ExitVServerCommand
import icu.h2l.login.vServer.command.OverVServerCommand
import icu.h2l.login.listener.PlayerAreaLifecycleListener
import icu.h2l.login.manager.HyperChatCommandManagerImpl
import icu.h2l.login.manager.HyperZonePlayerManager
import icu.h2l.login.message.MessageKeys
import icu.h2l.login.message.MessageService
import icu.h2l.login.listener.LoginRenameListener
import icu.h2l.login.listener.LoginReUuidListener
import icu.h2l.login.listener.LoginVerifyListener
import icu.h2l.login.listener.AttachedProfileInitialGameProfileListener
import icu.h2l.login.module.EmbeddedModuleRegistry
import icu.h2l.login.module.EmbeddedModuleSpec
import icu.h2l.login.profile.ProfileBindingCodeService
import icu.h2l.login.vServer.backend.compat.BackendRuntimeProfileCompensator
import icu.h2l.login.vServer.backend.compat.BackendProfileLayerCompatListener
import icu.h2l.login.profile.VelocityHyperZoneProfileService
import icu.h2l.login.util.registerApiLogger
import net.kyori.adventure.text.logger.slf4j.ComponentLogger
import org.spongepowered.configurate.ConfigurationNode
import org.spongepowered.configurate.ConfigurationOptions
import org.spongepowered.configurate.hocon.HoconConfigurationLoader
import org.spongepowered.configurate.kotlin.dataClassFieldDiscoverer
import org.spongepowered.configurate.objectmapping.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path

@Suppress("ANNOTATION_WILL_BE_APPLIED_ALSO_TO_PROPERTY_OR_FIELD")
class HyperZoneLoginMain(
    private val server: ProxyServer,
    val logger: ComponentLogger,
    val dataDirectory: Path,
    private val plugin: HyperZoneApi
) {
    private val slowTestOverRegistration = HyperChatCommandRegistration(
        name = "over",
        executor = OverVServerCommand()
    )
    @Volatile
    private var slowTestCommandRegistered = false

    var activeVServerAdapter: HyperZoneVServerAdapter? = null
    lateinit var databaseManager: icu.h2l.login.manager.DatabaseManager
    lateinit var databaseHelper: DatabaseHelper
    lateinit var profileService: VelocityHyperZoneProfileService
    lateinit var backendRuntimeProfileCompensator: BackendRuntimeProfileCompensator
    lateinit var bindingCodeService: ProfileBindingCodeService
    lateinit var messageService: MessageService
    val serverAdapter: HyperZoneVServerAdapter?
        get() = activeVServerAdapter
    val hyperZonePlayers: HyperZonePlayerAccessor
        get() = HyperZonePlayerManager
    val chatCommandManager: HyperChatCommandManager
        get() = HyperChatCommandManagerImpl


    companion object {
        private lateinit var instance: HyperZoneLoginMain
        private lateinit var databaseSourceConfig: DatabaseSourceConfig
        private lateinit var remapConfig: RemapConfig
        private lateinit var miscConfig: MiscConfig
        private lateinit var debugConfig: DebugConfig
        private lateinit var modulesConfig: ModulesConfig
        private lateinit var backendServerConfig: BackendServerConfig
        private lateinit var outPreConfig: OutPreConfig
        private lateinit var messagesConfig: MessagesConfig

        @JvmStatic
        fun getInstance(): HyperZoneLoginMain = instance

        @JvmStatic
        fun getRemapConfig(): RemapConfig = remapConfig

        @JvmStatic
        fun getMiscConfig(): MiscConfig = miscConfig

        @JvmStatic
        fun getDebugConfig(): DebugConfig = debugConfig

        @JvmStatic
        fun getBackendServerConfig(): BackendServerConfig = backendServerConfig

        @JvmStatic
        fun getOutPreConfig(): OutPreConfig = outPreConfig

        @JvmStatic
        fun getMessagesConfig(): MessagesConfig = messagesConfig
    }

    init {
        instance = this
    }

    @Suppress("unused", "UNUSED_PARAMETER")
    fun onEnable(event: ProxyInitializeEvent) {
        registerApiLogger()
        loadDatabaseConfig()
        loadRemapConfig()
        loadMiscConfig()
        loadDebugConfig()
        loadModulesConfig()
        loadBackendServerConfig()
        loadOutPreConfig()
        loadMessagesConfig()
        messageService = MessageService(dataDirectory, logger)
        messageService.load(messagesConfig)
        HyperZoneMessageServiceProvider.bind(messageService)
        connectDatabase()
        // 创建基础表（Profile 表等）
        createBaseTables()
        profileService = VelocityHyperZoneProfileService(databaseHelper)
        backendRuntimeProfileCompensator = BackendRuntimeProfileCompensator(profileService, logger)
        bindingCodeService = ProfileBindingCodeService(
            BindingCodeRepository(databaseManager, databaseManager.getBindingCodeTable()),
            profileService
        )
        HyperZoneProfileServiceProvider.bind(profileService)

        activeVServerAdapter = null

        val configuredMode = normalizeVServerMode(backendServerConfig.vServerMode)
        val configuredFallback = backendServerConfig.fallbackAuthServer.trim()
        val configuredOutPreAuthAddress = outPreConfig.resolveAuthAddress()
        if (configuredOutPreAuthAddress != null && configuredMode == "outpre") {
            activeVServerAdapter = OutPreVServerAuth(server)
            logger.info(
                "Using outpre waiting-area adapter on direct auth endpoint '{}' ({})",
                outPreConfig.authTargetLabel(),
                configuredOutPreAuthAddress,
            )
        } else if (configuredFallback.isNotBlank() && configuredMode == "backend") {
            activeVServerAdapter = BackendAuthHoldListener(server)
            logger.info("Using backend auth hold server '$configuredFallback'")
        } else {
            logger.info(
                if (configuredMode == "outpre") {
                    "Outpre mode is enabled but vserver-outpre.conf authHost/authPort is invalid; running without waiting-area adapter"
                } else {
                    "Backend mode is enabled but fallbackAuthServer is blank; running without waiting-area adapter"
                }
            )
        }

        HyperChatCommandManagerImpl.bindVServer(proxy, activeVServerAdapter)
        activeVServerAdapter?.let { proxy.eventManager.register(plugin, it) }

        chatCommandManager.register(
            HyperChatCommandRegistration(
                name = "exit",
                executor = ExitVServerCommand()
            )
        )
        chatCommandManager.register(
            HyperChatCommandRegistration(
                name = "rename",
                executor = RenameCommand(),
                brigadier = RenameCommand.brigadier()
            )
        )
        chatCommandManager.register(
            HyperChatCommandRegistration(
                name = "reUUID",
                aliases = setOf("reuuid", "reUuid"),
                executor = ReUuidCommand(),
                brigadier = ReUuidCommand.brigadier()
            )
        )
        BindCodeCommandRegistrar.register(chatCommandManager, bindingCodeService)
        syncSlowTestCommands()

//        最后加载模块
        // Keep internal modules that are part of the main plugin
        registerModule(VelocityNetworkModule(), plugin)
        registerConfiguredEmbeddedModules()
        // External modules (auth-offline, auth-yggd, data-merge) will be loaded as
        // separate Velocity plugins and should call `registerModule(...)` on this
        // main plugin during their own initialization.
        val hzlCommand = HyperZoneLoginCommand(bindingCodeService).createCommand()
        val hzlCommandMeta = proxy.commandManager.metaBuilder(hzlCommand).build()
        proxy.commandManager.register(hzlCommandMeta, hzlCommand)
        if (activeVServerAdapter?.needsBackendInitialProfileCompat() == true) {
            proxy.eventManager.register(plugin, BackendProfileLayerCompatListener())
        }
        proxy.eventManager.register(plugin, AttachedProfileInitialGameProfileListener())
        proxy.eventManager.register(plugin, backendRuntimeProfileCompensator)
        proxy.eventManager.register(plugin, LoginRenameListener())
        proxy.eventManager.register(plugin, LoginReUuidListener())
        proxy.eventManager.register(plugin, LoginVerifyListener())
        proxy.eventManager.register(plugin, PlayerAreaLifecycleListener)
        proxy.eventManager.register(plugin, HyperZonePlayerManager)

        logInternalTestWarning()

    }

    val proxy: ProxyServer
        get() = server

    fun registerModule(module: HyperSubModule, api: HyperZoneApi) {
        try {
            module.register(api)
            logger.info("模块加载成功: ${module.javaClass.name}")
        } catch (e: Exception) {
            logger.error("加载模块 ${module.javaClass.name} 失败: ${e.message}", e)
        }
    }

    private fun registerConfiguredEmbeddedModules() {
        registerEmbeddedModule(EmbeddedModuleRegistry.authFloodgate, modulesConfig.authFloodgate)
        registerEmbeddedModule(EmbeddedModuleRegistry.authOffline, modulesConfig.authOffline)
        registerEmbeddedModule(EmbeddedModuleRegistry.authYggd, modulesConfig.authYggd)
        registerEmbeddedModule(EmbeddedModuleRegistry.safe, modulesConfig.safe)
        registerEmbeddedModule(EmbeddedModuleRegistry.profileSkin, modulesConfig.profileSkin)
        registerEmbeddedModule(EmbeddedModuleRegistry.dataMerge, modulesConfig.dataMerge)
    }

    private fun registerEmbeddedModule(spec: EmbeddedModuleSpec, enabled: Boolean) {
        if (!enabled) {
            logger.info("内置模块已禁用: ${spec.displayName} (modules.conf -> ${spec.configKey}=false)")
            return
        }

        if (proxy.pluginManager.getPlugin(spec.externalPluginId).isPresent) {
            logger.info("检测到外部插件 ${spec.externalPluginId}，跳过内置模块 ${spec.displayName}")
            return
        }

        val missingRequiredPlugins = spec.requiredPluginIds.filter { requiredPluginId ->
            !proxy.pluginManager.getPlugin(requiredPluginId).isPresent
        }
        if (missingRequiredPlugins.isNotEmpty()) {
            logger.info(
                "内置模块 ${spec.displayName} 依赖插件缺失: ${missingRequiredPlugins.joinToString()}，已跳过"
            )
            return
        }

        val embeddedModule = try {
            EmbeddedModuleRegistry.instantiate(spec, javaClass.classLoader)
        } catch (e: Throwable) {
            logger.error("内置模块 ${spec.displayName} 实例化失败: ${e.message}", e)
            return
        }

        if (embeddedModule == null) {
            logger.info("当前主 jar 未内置模块 ${spec.displayName}，已跳过；如需单文件分发，请使用 monolith 产物")
            return
        }

        registerModule(embeddedModule, plugin)
    }

    /**
     * Trigger re-join authentication flow in the active waiting-area implementation.
     */
    fun triggerVServerReJoinForPlayer(player: com.velocitypowered.api.proxy.Player) {
        serverAdapter?.reJoin(player)
            ?: messageService.send(player, MessageKeys.HzlCommand.AUTH_FLOW_UNAVAILABLE)
    }

    private fun normalizeVServerMode(rawMode: String): String {
        return when (rawMode.trim().lowercase()) {
            "", "auto", "limbo" -> {
                logger.warn("vServerMode='{}' is deprecated after Limbo removal; falling back to 'backend'", rawMode)
                "backend"
            }

            "outpre" -> "outpre"
            else -> "backend"
        }
    }

    private fun logInternalTestWarning() {
        logger.warn("========================================")
        logger.warn("=== ⚠ 内测版本，可能有 bug，请勿分发 ===")
        logger.warn("========================================")
    }

    fun reloadRuntimeConfigs() {
        loadRemapConfig()
        loadMiscConfig()
        loadDebugConfig()
        loadModulesConfig()
        loadBackendServerConfig()
        loadOutPreConfig()
        loadMessagesConfig()
        if (::messageService.isInitialized) {
            messageService.load(messagesConfig)
        }
        syncSlowTestCommands()
    }

    private fun syncSlowTestCommands() {
        if (debugConfig.slowTest.enabled) {
            if (!slowTestCommandRegistered) {
                chatCommandManager.register(slowTestOverRegistration)
                slowTestCommandRegistered = true
            }
            return
        }

        if (slowTestCommandRegistered) {
            chatCommandManager.unregister(slowTestOverRegistration.name)
            slowTestCommandRegistered = false
        }
    }

    private fun loadDatabaseConfig() {
        val path = dataDirectory.resolve("database.conf")
        val firstCreation = Files.notExists(path)
        val loader = HoconConfigurationLoader.builder()
            .defaultOptions { opts: ConfigurationOptions ->
                opts
                    .shouldCopyDefaults(true)
                    .header(
                        """
                            HyperZoneLogin Database Configuration | by ksqeib
                            
                        """.trimIndent()
                    ).serializers { s ->
                        s.registerAnnotatedObjects(
                            ObjectMapper.factoryBuilder().addDiscoverer(dataClassFieldDiscoverer()).build()
                        )
                    }
            }
            .path(path)
            .build()
        val node = loader.load()
        val config = node.get(DatabaseSourceConfig::class.java)
        if (firstCreation) {
            node.set(config)
            loader.save(node)
        }
        if (config != null) {
            databaseSourceConfig = config
        }
    }
    
    private fun loadRemapConfig() {
        val path = dataDirectory.resolve("remap.conf")
        val firstCreation = Files.notExists(path)
        val loader = HoconConfigurationLoader.builder()
            .defaultOptions { opts: ConfigurationOptions ->
                opts
                    .shouldCopyDefaults(true)
                    .header(
                        """
                            HyperZoneLogin Remap Configuration | by ksqeib
                            
                        """.trimIndent()
                    ).serializers { s ->
                        s.registerAnnotatedObjects(
                            ObjectMapper.factoryBuilder().addDiscoverer(dataClassFieldDiscoverer()).build()
                        )
                    }
            }
            .path(path)
            .build()
        val node = loader.load()
        val config = node.get(RemapConfig::class.java)
        if (firstCreation) {
            node.set(config)
            loader.save(node)
        }
        if (config != null) {
            remapConfig = config
        }
    }

    private fun loadMiscConfig() {
        val path = dataDirectory.resolve("misc.conf")
        val firstCreation = Files.notExists(path)
        val loader = HoconConfigurationLoader.builder()
            .defaultOptions { opts: ConfigurationOptions ->
                opts
                    .shouldCopyDefaults(true)
                    .header(
                        """
                            HyperZoneLogin Misc Configuration | by ksqeib
                            
                        """.trimIndent()
                    ).serializers { s ->
                        s.registerAnnotatedObjects(
                            ObjectMapper.factoryBuilder().addDiscoverer(dataClassFieldDiscoverer()).build()
                        )
                    }
            }
            .path(path)
            .build()
        val node = loader.load()
        val config = readMiscConfig(node)
        val shouldPersistDefaults = firstCreation || hasLegacyMiscLayout(node)
        if (shouldPersistDefaults) {
            node.set(config)
            loader.save(node)
        }
        miscConfig = config
    }

    private fun readMiscConfig(node: ConfigurationNode): MiscConfig {
        val loaded = runCatching {
            node.get(MiscConfig::class.java)
        }.getOrNull() ?: MiscConfig()

        val legacyEnableNameHotChange = node.node("enableNameHotChange").getBooleanOrNull()
        val legacyEnableUuidHotChange = node.node("enableUuidHotChange").getBooleanOrNull()
        val legacyEmbeddedEnableNameHotChange = node.node("debug", "enableNameHotChange").getBooleanOrNull()
        val legacyEmbeddedEnableUuidHotChange = node.node("debug", "enableUuidHotChange").getBooleanOrNull()

        return loaded.copy(
            enableNameHotChange = legacyEnableNameHotChange
                ?: legacyEmbeddedEnableNameHotChange
                ?: loaded.enableNameHotChange,
            enableUuidHotChange = legacyEnableUuidHotChange
                ?: legacyEmbeddedEnableUuidHotChange
                ?: loaded.enableUuidHotChange
        )
    }

    private fun hasLegacyMiscLayout(node: ConfigurationNode): Boolean {
        return !node.node("enableNameHotChange").virtual()
            || !node.node("enableUuidHotChange").virtual()
    }

    private fun loadDebugConfig() {
        val path = dataDirectory.resolve("debug.conf")
        val firstCreation = Files.notExists(path)
        val loader = HoconConfigurationLoader.builder()
            .defaultOptions { opts: ConfigurationOptions ->
                opts
                    .shouldCopyDefaults(true)
                    .header(
                        """
                            HyperZoneLogin Debug Configuration | by ksqeib
                            包含 debug 日志与慢测试功能开关。

                        """.trimIndent()
                    ).serializers { s ->
                        s.registerAnnotatedObjects(
                            ObjectMapper.factoryBuilder().addDiscoverer(dataClassFieldDiscoverer()).build()
                        )
                    }
            }
            .path(path)
            .build()
        val node = loader.load()
        val config = readDebugConfig(node)
        if (firstCreation) {
            node.set(config)
            loader.save(node)
        }
        debugConfig = config
    }

    private fun readDebugConfig(node: ConfigurationNode): DebugConfig {
        return runCatching {
            node.get(DebugConfig::class.java)
        }.getOrNull() ?: DebugConfig()
    }

    private fun ConfigurationNode.getBooleanOrNull(): Boolean? {
        return if (virtual()) null else getBoolean()
    }

    private fun loadModulesConfig() {
        val path = dataDirectory.resolve("modules.conf")
        val firstCreation = Files.notExists(path)
        val loader = HoconConfigurationLoader.builder()
            .defaultOptions { opts: ConfigurationOptions ->
                opts
                    .shouldCopyDefaults(true)
                    .header(
                        """
                            HyperZoneLogin Embedded Modules Configuration | by ksqeib
                            在单文件版中控制内置模块是否启用；若同名外部插件已安装，则自动跳过内置版本。
                            
                        """.trimIndent()
                    ).serializers { s ->
                        s.registerAnnotatedObjects(
                            ObjectMapper.factoryBuilder().addDiscoverer(dataClassFieldDiscoverer()).build()
                        )
                    }
            }
            .path(path)
            .build()
        val node = loader.load()
        val config = node.get(ModulesConfig::class.java)
        if (firstCreation) {
            node.set(config)
            loader.save(node)
        }
        if (config != null) {
            modulesConfig = config
        }
    }

    private fun loadBackendServerConfig() {
        val path = dataDirectory.resolve("backend-server.conf")
        val firstCreation = Files.notExists(path)
        val loader = HoconConfigurationLoader.builder()
            .defaultOptions { opts: ConfigurationOptions ->
                opts
                    .shouldCopyDefaults(true)
                    .header(
                        """
                            HyperZoneLogin Backend Server Configuration | by ksqeib
                            
                        """.trimIndent()
                    ).serializers { s ->
                        s.registerAnnotatedObjects(
                            ObjectMapper.factoryBuilder().addDiscoverer(dataClassFieldDiscoverer()).build()
                        )
                    }
            }
            .path(path)
            .build()
        val node = loader.load()
        val config = node.get(BackendServerConfig::class.java)
        if (firstCreation) {
            node.set(config)
            loader.save(node)
        }
        if (config != null) {
            backendServerConfig = config
        }
    }

    private fun loadOutPreConfig() {
        val path = dataDirectory.resolve("vserver-outpre.conf")
        val firstCreation = Files.notExists(path)
        val loader = HoconConfigurationLoader.builder()
            .defaultOptions { opts: ConfigurationOptions ->
                opts
                    .shouldCopyDefaults(true)
                    .header(
                        """
                            HyperZoneLogin OutPre Configuration | by ksqeib
                            outpre 模式的认证服、认证后目标服，以及对认证服暴露的 Host / Port / Player IP 都只在这里配置。

                        """.trimIndent()
                    ).serializers { s ->
                        s.registerAnnotatedObjects(
                            ObjectMapper.factoryBuilder().addDiscoverer(dataClassFieldDiscoverer()).build()
                        )
                    }
            }
            .path(path)
            .build()
        val node = loader.load()
        val config = node.get(OutPreConfig::class.java)
        if (firstCreation) {
            node.set(config)
            loader.save(node)
        }
        if (config != null) {
            outPreConfig = config
        }
    }

    private fun loadMessagesConfig() {
        val path = dataDirectory.resolve("messages.conf")
        val firstCreation = Files.notExists(path)
        val loader = HoconConfigurationLoader.builder()
            .defaultOptions { opts: ConfigurationOptions ->
                opts
                    .shouldCopyDefaults(true)
                    .header(
                        """
                            HyperZoneLogin Messages Configuration | by ksqeib
                            具体文案文件位于 messages/ 目录，可分别编辑 en_us.conf / zh_cn.conf / ru_ru.conf。
                            
                        """.trimIndent()
                    ).serializers { s ->
                        s.registerAnnotatedObjects(
                            ObjectMapper.factoryBuilder().addDiscoverer(dataClassFieldDiscoverer()).build()
                        )
                    }
            }
            .path(path)
            .build()
        val node = loader.load()
        val config = node.get(MessagesConfig::class.java)
        if (firstCreation) {
            node.set(config)
            loader.save(node)
        }
        if (config != null) {
            messagesConfig = config
        }
    }

    private fun connectDatabase() {
        logger.info("正在初始化数据库...")
        
        val dbConfig = when (databaseSourceConfig.type.uppercase()) {
            "SQLITE" -> {
                val dbPath = dataDirectory.resolve(databaseSourceConfig.sqlite.path)
                // 确保数据库文件的父目录存在
                dbPath.parent?.let { Files.createDirectories(it) }
                DatabaseConfig.sqlite(
                    path = dbPath.toString(),
                    tablePrefix = databaseSourceConfig.tablePrefix,
                    connectionTimeout = databaseSourceConfig.pool.connectionTimeout,
                    idleTimeout = databaseSourceConfig.pool.idleTimeout,
                    maxLifetime = databaseSourceConfig.pool.maxLifetime
                )
            }
            "MYSQL" -> {
                DatabaseConfig.mysql(
                    host = databaseSourceConfig.mysql.host,
                    port = databaseSourceConfig.mysql.port,
                    database = databaseSourceConfig.mysql.database,
                    username = databaseSourceConfig.mysql.username,
                    password = databaseSourceConfig.mysql.password,
                    tablePrefix = databaseSourceConfig.tablePrefix,
                    parameters = databaseSourceConfig.mysql.parameters,
                    driverClassName = databaseSourceConfig.mysql.driverClassName,
                    maximumPoolSize = databaseSourceConfig.pool.maximumPoolSize,
                    minimumIdle = databaseSourceConfig.pool.minimumIdle,
                    connectionTimeout = databaseSourceConfig.pool.connectionTimeout,
                    idleTimeout = databaseSourceConfig.pool.idleTimeout,
                    maxLifetime = databaseSourceConfig.pool.maxLifetime
                )
            }
            "MARIADB" -> {
                DatabaseConfig.mariadb(
                    host = databaseSourceConfig.mariadb.host,
                    port = databaseSourceConfig.mariadb.port,
                    database = databaseSourceConfig.mariadb.database,
                    username = databaseSourceConfig.mariadb.username,
                    password = databaseSourceConfig.mariadb.password,
                    tablePrefix = databaseSourceConfig.tablePrefix,
                    parameters = databaseSourceConfig.mariadb.parameters,
                    driverClassName = databaseSourceConfig.mariadb.driverClassName,
                    maximumPoolSize = databaseSourceConfig.pool.maximumPoolSize,
                    minimumIdle = databaseSourceConfig.pool.minimumIdle,
                    connectionTimeout = databaseSourceConfig.pool.connectionTimeout,
                    idleTimeout = databaseSourceConfig.pool.idleTimeout,
                    maxLifetime = databaseSourceConfig.pool.maxLifetime
                )
            }
            "H2" -> {
                throw IllegalArgumentException(
                    "核心模块已不再支持 H2 数据库，请改用 SQLITE/MYSQL/MARIADB。若需要读取旧 H2 数据，请使用 data-merge 模块。"
                )
            }
            else -> {
                logger.error("不支持的数据库类型: ${databaseSourceConfig.type}, 使用默认 SQLite")
                val dbPath = dataDirectory.resolve(databaseSourceConfig.sqlite.path)
                // 确保数据库文件的父目录存在
                dbPath.parent?.let { Files.createDirectories(it) }
                DatabaseConfig.sqlite(
                    path = dbPath.toString(),
                    tablePrefix = databaseSourceConfig.tablePrefix
                )
            }
        }
        
        databaseManager = icu.h2l.login.manager.DatabaseManager(
            config = dbConfig,
            proxy = proxy
        )
        
        databaseManager.connect()
        databaseHelper = DatabaseHelper(databaseManager)
        
        logger.info("数据库连接完成")
    }
    
    private fun createBaseTables() {
        logger.info("正在创建基础数据表...")
        databaseManager.createBaseTables()
        logger.info("基础数据表创建完成")
    }
}

