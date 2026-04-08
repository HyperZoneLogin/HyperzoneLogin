package icu.h2l.login

import com.google.inject.Inject
import com.google.inject.Injector
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import icu.h2l.api.command.HyperChatCommandManager
import icu.h2l.api.command.HyperChatCommandManagerProvider
import icu.h2l.api.command.HyperChatCommandRegistration
import icu.h2l.api.vServer.HyperZoneVServerAdapter
import icu.h2l.api.vServer.HyperZoneVServerProvider
import icu.h2l.api.module.HyperSubModule
import icu.h2l.api.player.HyperZonePlayerAccessor
import icu.h2l.api.player.HyperZonePlayerAccessorProvider
// Module implementations (auth-offline, auth-yggd, data-merge) are now separate plugins
// and will register themselves with the main plugin at runtime. Do not import them here.
import icu.h2l.login.command.HyperZoneLoginCommand
import icu.h2l.login.config.DatabaseSourceConfig
import icu.h2l.login.config.RemapConfig
import icu.h2l.login.config.MiscConfig
import icu.h2l.login.database.DatabaseConfig
import icu.h2l.login.database.DatabaseHelper
import icu.h2l.login.inject.network.VelocityNetworkModule
import icu.h2l.login.vServer.backend.BackendAuthHoldListener
import icu.h2l.login.vServer.limbo.LimboVServerAuth
import icu.h2l.login.vServer.limbo.command.ExitLimboCommand
import icu.h2l.login.listener.EventListener
import icu.h2l.login.manager.HyperChatCommandManagerImpl
import icu.h2l.login.manager.HyperZonePlayerManager
import icu.h2l.login.util.registerApiLogger
import net.kyori.adventure.text.logger.slf4j.ComponentLogger
import org.spongepowered.configurate.ConfigurationOptions
import org.spongepowered.configurate.hocon.HoconConfigurationLoader
import org.spongepowered.configurate.kotlin.dataClassFieldDiscoverer
import org.spongepowered.configurate.objectmapping.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path

@Suppress("ANNOTATION_WILL_BE_APPLIED_ALSO_TO_PROPERTY_OR_FIELD")
class HyperZoneLoginMain @Inject constructor(
    private val server: ProxyServer,
    val logger: ComponentLogger,
    @DataDirectory private val dataDirectory: Path,
    private val injector: Injector
) : HyperZoneVServerProvider, HyperZonePlayerAccessorProvider, HyperChatCommandManagerProvider {
    var limboServerManager: LimboVServerAuth? = null
    var backendAuthHoldListener: BackendAuthHoldListener? = null
    lateinit var databaseManager: icu.h2l.login.manager.DatabaseManager
    lateinit var databaseHelper: DatabaseHelper
    override val serverAdapter: HyperZoneVServerAdapter?
        get() = limboServerManager
    override val hyperZonePlayers: HyperZonePlayerAccessor
        get() = HyperZonePlayerManager
    override val chatCommandManager: HyperChatCommandManager
        get() = HyperChatCommandManagerImpl


    companion object {
        private lateinit var instance: HyperZoneLoginMain
        private lateinit var databaseSourceConfig: DatabaseSourceConfig
        private lateinit var remapConfig: RemapConfig
        private lateinit var miscConfig: MiscConfig

        @JvmStatic
        fun getInstance(): HyperZoneLoginMain = instance

        @JvmStatic
        fun getDatabaseConfig(): DatabaseSourceConfig = databaseSourceConfig
        
        @JvmStatic
        fun getRemapConfig(): RemapConfig = remapConfig

        @JvmStatic
        fun getMiscConfig(): MiscConfig = miscConfig
    }

    init {
        instance = this
    }

    @Subscribe
    fun onEnable(event: ProxyInitializeEvent) {
        registerApiLogger()
        loadDatabaseConfig()
        loadRemapConfig()
        loadMiscConfig()
        connectDatabase()
        // 创建基础表（Profile 表等）
        createBaseTables()

        // Soft-dependency: only create/load Limbo adapter when the limboapi plugin is present
        val limboPluginPresent = server.pluginManager.getPlugin("limboapi").isPresent
        if (limboPluginPresent) {
            try {
                val limbo = LimboVServerAuth(server)
                limbo.load()
                limboServerManager = limbo
                backendAuthHoldListener = null
                // bind adapter (not the third-party Limbo type)
                HyperChatCommandManagerImpl.bindLimbo(proxy, limbo)
                HyperChatCommandManagerImpl.setProxyFallbackCommandsEnabled(false)
                proxy.eventManager.register(this, limbo)
            } catch (t: Throwable) {
                logger.warn("Limbo plugin detected but initialization failed: ${t.message}")
            }
        } else {
            // No limbo present; bind null adapter so command registration is a no-op
            HyperChatCommandManagerImpl.bindLimbo(proxy, null)
            val configuredFallback = miscConfig.fallbackAuthServer.trim()
            if (configuredFallback.isNotBlank()) {
                val backendHold = BackendAuthHoldListener(server)
                backendAuthHoldListener = backendHold
                HyperChatCommandManagerImpl.setProxyFallbackCommandsEnabled(true)
                proxy.eventManager.register(this, backendHold)
                logger.info("Limbo not present; using backend auth hold server '$configuredFallback'")
            } else {
                backendAuthHoldListener = null
                HyperChatCommandManagerImpl.setProxyFallbackCommandsEnabled(false)
                logger.info("Limbo not present; running without Limbo integration or backend auth hold")
            }
        }

        chatCommandManager.register(
            HyperChatCommandRegistration(
                name = "exit",
                command = ExitLimboCommand()
            )
        )

//        最后加载模块
        // Keep internal modules that are part of the main plugin
        registerModule(VelocityNetworkModule())
        // External modules (auth-offline, auth-yggd, data-merge) will be loaded as
        // separate Velocity plugins and should call `registerModule(...)` on this
        // main plugin during their own initialization.



        proxy.commandManager.register("hzl", HyperZoneLoginCommand())
        proxy.eventManager.register(this, EventListener())
        proxy.eventManager.register(this, HyperZonePlayerManager)
        // If Limbo was present, we've already registered its event listener above

        logInternalTestWarning()

    }

    val proxy: ProxyServer
        get() = server

    fun registerModule(module: HyperSubModule) {
        try {
            module.register(this, proxy, dataDirectory, databaseManager)
            logger.info("模块加载成功: ${module.javaClass.name}")
        } catch (e: Exception) {
            logger.error("加载模块 ${module.javaClass.name} 失败: ${e.message}", e)
        }
    }

    /**
     * Trigger authentication flow in Limbo for a proxy player if Limbo is present.
     * Safe no-op if Limbo integration is not available.
     */
    fun triggerLimboAuthForPlayer(player: com.velocitypowered.api.proxy.Player) {
        limboServerManager?.authPlayer(player)
            ?: backendAuthHoldListener?.authPlayer(player)
            ?: player.sendPlainMessage("§c当前未启用可用的认证等待流程")
    }

    private fun logInternalTestWarning() {
        logger.warn("========================================")
        logger.warn("=== ⚠ 内测版本，可能有 bug，请勿分发 ===")
        logger.warn("========================================")
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
        val config = node.get(MiscConfig::class.java)
        if (firstCreation) {
            node.set(config)
            loader.save(node)
        }
        if (config != null) {
            miscConfig = config
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
                    maximumPoolSize = databaseSourceConfig.pool.maximumPoolSize,
                    minimumIdle = databaseSourceConfig.pool.minimumIdle,
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
                val dbPath = dataDirectory.resolve(databaseSourceConfig.h2.path)
                // 确保数据库文件的父目录存在
                dbPath.parent?.let { Files.createDirectories(it) }
                DatabaseConfig.h2(
                    path = dbPath.toString(),
                    tablePrefix = databaseSourceConfig.tablePrefix,
                    maximumPoolSize = databaseSourceConfig.pool.maximumPoolSize,
                    minimumIdle = databaseSourceConfig.pool.minimumIdle,
                    connectionTimeout = databaseSourceConfig.pool.connectionTimeout,
                    idleTimeout = databaseSourceConfig.pool.idleTimeout,
                    maxLifetime = databaseSourceConfig.pool.maxLifetime
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

