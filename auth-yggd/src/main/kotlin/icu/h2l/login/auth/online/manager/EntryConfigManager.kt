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

package icu.h2l.login.auth.online.manager

import com.velocitypowered.api.proxy.ProxyServer
import icu.h2l.api.log.debug
import icu.h2l.api.log.error
import icu.h2l.api.log.info
import icu.h2l.login.auth.online.config.entry.EntryConfig
import icu.h2l.login.auth.online.events.EntryRegisterEvent
import org.spongepowered.configurate.ConfigurationOptions
import org.spongepowered.configurate.hocon.HoconConfigurationLoader
import org.spongepowered.configurate.kotlin.dataClassFieldDiscoverer
import org.spongepowered.configurate.objectmapping.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * EntryConfig 管理器，负责从 entry 文件夹中加载所有配置文件
 */
class EntryConfigManager(
    private val dataDirectory: Path,
    private val proxyServer: ProxyServer
) {
    private val entryConfigs = mutableMapOf<String, EntryConfig>()

    companion object {
        private const val ENTRY_FOLDER = "entry"
        private const val EXAMPLE_FOLDER = "example"
        private const val CONFIG_EXTENSION = ".conf"
    }

    /**
     * 加载所有 entry 配置文件
     */
    fun loadAllConfigs() {
        val entryDir = dataDirectory.resolve(ENTRY_FOLDER)

        // 如果目录不存在，创建目录并生成默认配置
        if (Files.notExists(entryDir)) {
            Files.createDirectories(entryDir)
            createDefaultConfigs(entryDir)
            createExampleConfig(entryDir)
            debug { "创建 entry 目录和默认配置文件" }
        }

        // 扫描并加载所有配置文件
        scanAndLoadConfigs(entryDir)

        info { "成功加载 ${entryConfigs.size} 个 Entry 配置文件" }
    }

    /**
     * 扫描目录并加载配置文件
     */
    private fun scanAndLoadConfigs(directory: Path) {
        if (!directory.isDirectory()) return

        directory.listDirectoryEntries().forEach { path ->
            when {
                // 跳过 example 文件夹
                path.isDirectory() && path.name.equals(EXAMPLE_FOLDER, ignoreCase = true) -> {
                    debug { "跳过 example 文件夹: ${path.name}" }
                }
                // 递归扫描子目录
//                path.isDirectory() -> {
//                    scanAndLoadConfigs(path)
//                }
                // 加载 conf 配置文件
                path.name.endsWith(CONFIG_EXTENSION, ignoreCase = true) -> {
                    loadConfig(path)
                }
            }
        }
    }

    /**
     * 加载单个配置文件
     */
    private fun loadConfig(path: Path) {
        try {
            val loader = HoconConfigurationLoader.builder()
                .defaultOptions { opts: ConfigurationOptions ->
                    opts
                        .shouldCopyDefaults(true)
                        .header(
                            """
                            HyperZoneLogin Entry Configuration
                            配置文件格式为 HOCON
                            
                            """.trimIndent()
                        )
                        .serializers { s ->
                            s.registerAnnotatedObjects(
                                ObjectMapper.factoryBuilder()
                                    .addDiscoverer(dataClassFieldDiscoverer())
                                    .build()
                            )
                        }
                }
                .path(path)
                .build()

            val node = loader.load()
            val config = node.get(EntryConfig::class.java)
                ?: run {
                    error { "无法解析配置文件: ${path.fileName}" }
                    return
                }

            val resolvedUrl = config.yggdrasil.url.trim()

            // 验证配置有效性
            // 检查 ID 是否重复
            if (entryConfigs.values.any { it.id == config.id }) {
                error { "配置文件 ${path.fileName} 的 ID ${config.id} 与其他配置重复，跳过加载" }
                return
            }

            if (resolvedUrl.isBlank()) {
                error {
                    "配置文件 ${path.fileName} 的 yggdrasilAuth.url 为空，跳过加载。" +
                        "当前读取路径: ${path.toAbsolutePath()}。" +
                        "请确认你修改的是插件数据目录下的 entry/*.conf，且不要放在 entry/example 中；" +
                        "src/main/resources/example 下的示例文件也不会在运行时直接加载。"
                }
                return
            }

            val configName = path.fileName.toString().removeSuffix(CONFIG_EXTENSION)
            entryConfigs[configName] = config
            debug { "成功加载配置: $configName (ID: ${config.id}, Name: ${config.name}, Url: $resolvedUrl)" }

            // 发布 Entry 注册事件，并等待相关注册与建表逻辑完成
            proxyServer.eventManager.fire(EntryRegisterEvent(configName, config)).join()
        } catch (e: Exception) {
            error(e) { "加载配置文件 ${path.fileName} 时出错: ${e.message}" }
        }
    }

    /**
     * 创建示例配置文件
     */
    private fun createExampleConfig(entryDir: Path) {
        val exampleDir = entryDir.resolve(EXAMPLE_FOLDER)
        Files.createDirectories(exampleDir)

        val examplePath = exampleDir.resolve("example$CONFIG_EXTENSION")
        createConfigFile(
            path = examplePath,
            config = EntryConfig(),
            header =
                """
                HyperZoneLogin Entry Configuration - Example
                这是一个示例配置文件，位于 example 文件夹中的配置不会被加载
                复制此文件到 entry 文件夹（非 example 子文件夹）中并修改即可使用
                
                """.trimIndent()
        )

        debug { "创建示例配置文件: ${examplePath.fileName}" }
    }

    /**
     * 创建默认配置文件（Mojang）
     */
    private fun createDefaultConfigs(entryDir: Path) {
        val mojangPath = entryDir.resolve("mojang$CONFIG_EXTENSION")
        val mojangConfig = EntryConfig().apply {
            id = "mojang"
            name = "Mojang Official"
            yggdrasil = EntryConfig.YggdrasilAuthConfig().apply {
                url = "https://sessionserver.mojang.com/session/minecraft/hasJoined?username={username}&serverId={serverId}{ip}"
            }
        }

        createConfigFile(
            path = mojangPath,
            config = mojangConfig,
            header =
                """
                HyperZoneLogin Entry Configuration - Mojang
                Mojang 官方正版验证服务配置
                
                """.trimIndent()
        )

        debug { "创建默认配置文件: mojang.conf" }

        val littleskinPath = entryDir.resolve("littleskin$CONFIG_EXTENSION")
        val littleskinConfig = EntryConfig().apply {
            id = "littleskin"
            name = "Little Skin"
            yggdrasil = EntryConfig.YggdrasilAuthConfig().apply {
                url = "https://littleskin.cn/api/yggdrasil/sessionserver/session/minecraft/hasJoined?username={username}&serverId={serverId}{ip}"
            }
        }

        createConfigFile(
            path = littleskinPath,
            config = littleskinConfig,
            header =
                """
                HyperZoneLogin Entry Configuration - Little Skin
                Little Skin 第三方验证服务配置
                
                """.trimIndent()
        )

        debug { "创建默认配置文件: littleskin.conf" }

        val elyByPath = entryDir.resolve("elyby$CONFIG_EXTENSION")
        val elyByConfig = EntryConfig().apply {
            id = "elyby"
            name = "Ely.by"
            yggdrasil = EntryConfig.YggdrasilAuthConfig().apply {
                url = "https://authserver.ely.by/session/hasJoined?username={username}&serverId={serverId}{ip}"
            }
        }

        createConfigFile(
            path = elyByPath,
            config = elyByConfig,
            header =
                """
                HyperZoneLogin Entry Configuration - Ely.by
                Ely.by 第三方验证服务配置
                
                """.trimIndent()
        )

        debug { "创建默认配置文件: elyby.conf" }
    }

    private fun createConfigFile(path: Path, config: EntryConfig, header: String) {
        val loader = HoconConfigurationLoader.builder()
            .defaultOptions { opts: ConfigurationOptions ->
                opts
                    .shouldCopyDefaults(true)
                    .header(header)
                    .serializers { s ->
                        s.registerAnnotatedObjects(
                            ObjectMapper.factoryBuilder()
                                .addDiscoverer(dataClassFieldDiscoverer())
                                .build()
                        )
                    }
            }
            .path(path)
            .build()

        val node = loader.createNode()
        node.set(EntryConfig::class.java, config)
        loader.save(node)
    }



    /**
     * 获取所有加载的配置
     */
    fun getAllConfigs(): Map<String, EntryConfig> = entryConfigs.toMap()

    /**
     * 根据配置名称获取配置
     */
    fun getConfig(name: String): EntryConfig? = entryConfigs[name]

    /**
     * 根据 ID 获取配置
     */
    fun getConfigById(id: String): EntryConfig? = entryConfigs.values.find { it.id == id }

    /**
     * 重新加载所有配置
     */
    fun reloadConfigs() {
        entryConfigs.clear()
        loadAllConfigs()
    }
}