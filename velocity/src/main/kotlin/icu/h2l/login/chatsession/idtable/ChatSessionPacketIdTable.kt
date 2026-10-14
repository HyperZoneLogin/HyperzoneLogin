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

package icu.h2l.login.chatsession.idtable

import icu.h2l.api.log.debug
import icu.h2l.api.log.warn
import icu.h2l.api.util.ConfigLoader
import icu.h2l.api.log.HyperZoneDebugType
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `CHAT_SESSION_UPDATE` 数据包 id 运行时表（可配置 + 自学习缓存）。
 *
 * ── 三层来源，优先级从低到高 ─────────────────────────────────────────────
 * 1. **内置默认**：代码里的 [ChatSessionUpdatePacketIdResolver]，保证开箱即用、
 *    配置文件丢失也不影响功能；
 * 2. **配置文件**：数据目录下的 `chat-session-packet-ids.conf`，服主可手工编辑或
 *    提前登记未发布版本；
 * 3. **自学习缓存**：结构匹配命中、但 id 匹配落空时，把观测到的 (协议号, id)
 *    追加进配置文件。
 *
 * ── 自学习的防污染约束（重要）────────────────────────────────────────────
 * 结构匹配是启发式，存在误判可能，因此学习**绝不会**污染已知版本：
 *
 * - 只在该协议号**严格大于**当前已知表中的最大起始协议号时才学习
 *   （即「未来版本」才会被记录）；已知版本上出现结构命中而 id 不命中，
 *   说明是结构误判，记录 debug 日志但不改表。
 * - [ChatSessionPacketIdConfig.autoLearn] 关闭时完全不写盘。
 * - 学习只影响**后续**包的 id 匹配；当前包的去留仍由结构匹配决定，不会因学习改变结果。
 *
 * ── 线程模型 ─────────────────────────────────────────────────────────────
 * [resolve] 在 netty event loop 上被调用，必须是纯内存读取（只读 volatile 快照）。
 * 写盘走单线程守护 executor + 去抖，绝不阻塞 event loop。
 */
object ChatSessionPacketIdTable {

    /** 配置文件相对数据目录的路径。 */
    private const val CONFIG_FILE_NAME = "chat-session-packet-ids.conf"

    /** 写盘去抖延迟（毫秒）。 */
    private const val FLUSH_DEBOUNCE_MILLIS = 5_000L

    /** 合法的数据包 id 上界（Minecraft 单状态包 id 远小于此值）。 */
    private const val MAX_PLAUSIBLE_PACKET_ID = 0xFF

    private val flushExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "h2l-chat-session-id-flush").apply { isDaemon = true }
    }

    private val flushScheduled = AtomicBoolean(false)

    /** 数据目录；未绑定时不做任何写盘。 */
    @Volatile
    private var dataDirectory: Path? = null

    /** 落盘配置快照；volatile 保证 event loop 读到的是完整对象。 */
    @Volatile
    private var config: ChatSessionPacketIdConfig = ChatSessionPacketIdConfig()

    /**
     * 合并后的查找表：(起始协议号 → id)，已按协议号升序。
     * 配置项覆盖同协议号的内置默认项。
     */
    @Volatile
    private var mergedEntries: List<ChatSessionPacketIdConfig.PacketIdEntry> = defaultEntries()

    /** 已知表的最大起始协议号，用于判断「是否未来版本」。 */
    @Volatile
    private var highestKnownProtocol: Int = defaultEntries().maxOfOrNull { it.fromProtocol } ?: 0

    /**
     * 从数据目录加载（首次运行会用内置默认表播种）。
     *
     * 应在插件启动早期、任何后端连接建立之前调用。
     */
    fun load(directory: Path) {
        dataDirectory = directory
        val loaded = runCatching {
            ConfigLoader.loadConfig<ChatSessionPacketIdConfig>(
                dataDirectory = directory,
                fileName = CONFIG_FILE_NAME,
                defaultProvider = { ChatSessionPacketIdConfig(packetIds = defaultEntries()) },
                postLoadHook = { _, current, firstCreation ->
                    // 首次创建（或旧文件缺少表项）时用内置默认表播种，
                    // 保证服主拿到的文件是一份可读可编辑的完整表，而非空表。
                    if (firstCreation || current.packetIds.isEmpty()) {
                        current.copy(packetIds = defaultEntries())
                    } else {
                        current
                    }
                },
                forceSaveHook = { _, firstCreation -> firstCreation },
            )
        }.onFailure { throwable ->
            warn { "[ChatSessionIdTable] 读取 $CONFIG_FILE_NAME 失败，回退到内置默认表：${throwable.message}" }
        }.getOrNull()

        config = loaded ?: ChatSessionPacketIdConfig(packetIds = defaultEntries())
        rebuildIndex()
    }

    /**
     * 解析 [protocolVersion] 对应的数据包 id。
     *
     * 纯内存操作，可安全地在 event loop 上调用。
     *
     * @return 命中时为对应 id；版本未知或早于功能引入版本时为 null。
     */
    fun resolve(protocolVersion: com.velocitypowered.api.network.ProtocolVersion?): Int? {
        val protocol = protocolVersion?.protocol ?: return null
        if (protocol < MIN_SUPPORTED_PROTOCOL) {
            return null
        }

        return mergedEntries.lastOrNull { protocol >= it.fromProtocol }?.packetId
    }

    /**
     * 记录一次「结构匹配命中、id 匹配落空」的观测。
     *
     * 仅在 [protocol] 严格大于当前已知表的最大起始协议号时才学习并写盘；
     * 其余情况视为结构误判，只记录 debug 日志。
     *
     * @return true 表示本次观测被学习并（异步）持久化。
     */
    fun observeUnresolvedId(protocolVersion: com.velocitypowered.api.network.ProtocolVersion?, packetId: Int): Boolean {
        val protocol = protocolVersion?.protocol ?: return false
        if (protocol < MIN_SUPPORTED_PROTOCOL) {
            return false
        }
        if (packetId !in 0..MAX_PLAUSIBLE_PACKET_ID) {
            debug(HyperZoneDebugType.NETWORK_REWRITE) {
                "[ChatSessionIdTable] 忽略不可疑包 id=$packetId (protocol=$protocol)"
            }
            return false
        }

        val snapshot = config
        if (!snapshot.autoLearn) {
            return false
        }

        // 关键防污染判断：只有未来版本才记录。
        if (protocol <= highestKnownProtocol) {
            debug(HyperZoneDebugType.NETWORK_REWRITE) {
                "[ChatSessionIdTable] protocol=$protocol 已在已知区间内，判定为结构误判，" +
                        "不写入 id=$packetId"
            }
            return false
        }

        val existing = snapshot.packetIds.firstOrNull { it.fromProtocol == protocol }
        if (existing != null) {
            if (existing.packetId == packetId) {
                return false
            }
            // 同一未来协议号观测到不同 id：保留首次学习结果，避免抖动。
            debug(HyperZoneDebugType.NETWORK_REWRITE) {
                "[ChatSessionIdTable] protocol=$protocol 已登记 id=${existing.packetId}，" +
                        "忽略冲突观测 id=$packetId"
            }
            return false
        }

        val updated = snapshot.copy(
            packetIds = snapshot.packetIds + ChatSessionPacketIdConfig.PacketIdEntry(
                fromProtocol = protocol,
                packetId = packetId,
            )
        )
        config = updated
        rebuildIndex()
        scheduleFlush()
        return true
    }

    /** 重新加载（供 `/hzl reload` 使用）。 */
    fun reload() {
        dataDirectory?.let(::load)
    }

    /** 卸载时同步落盘一次，避免去抖窗口内的学习结果丢失。 */
    fun flushNow() {
        val directory = dataDirectory ?: return
        runCatching { saveToDisk(directory, config) }
            .onFailure { throwable -> warn { "[ChatSessionIdTable] 落盘失败：${throwable.message}" } }
    }

    /** 内置默认表：直接取自代码常量，作为兜底与首次播种来源。 */
    private fun defaultEntries(): List<ChatSessionPacketIdConfig.PacketIdEntry> =
        ChatSessionUpdatePacketIdResolver.builtinEntries()

    /** 依据 [config] 与内置默认重建查找索引。 */
    private fun rebuildIndex() {
        val fromConfig = config.packetIds
            .filter { it.fromProtocol >= MIN_SUPPORTED_PROTOCOL }
            .filter { it.packetId in 0..MAX_PLAUSIBLE_PACKET_ID }

        val configProtocols = fromConfig.mapTo(HashSet()) { it.fromProtocol }
        val merged = (defaultEntries().filterNot { it.fromProtocol in configProtocols } + fromConfig)
            .sortedBy { it.fromProtocol }

        mergedEntries = merged
        highestKnownProtocol = merged.maxOfOrNull { it.fromProtocol } ?: MIN_SUPPORTED_PROTOCOL
    }

    /** 去抖写盘：窗口内的多次学习合并为一次 IO，且在独立线程执行。 */
    private fun scheduleFlush() {
        if (!flushScheduled.compareAndSet(false, true)) {
            return
        }
        try {
            flushExecutor.schedule(
                Runnable {
                    flushScheduled.set(false)
                    val directory = dataDirectory ?: return@Runnable
                    runCatching { saveToDisk(directory, config) }
                        .onFailure { throwable ->
                            warn { "[ChatSessionIdTable] 异步落盘失败：${throwable.message}" }
                        }
                },
                FLUSH_DEBOUNCE_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        } catch (_: Throwable) {
            flushScheduled.set(false)
        }
    }

    /** 借助 ConfigLoader 的保存路径落盘（与本项目其他运行时配置写法一致）。 */
    private fun saveToDisk(directory: Path, snapshot: ChatSessionPacketIdConfig) {
        ConfigLoader.loadConfig<ChatSessionPacketIdConfig>(
            dataDirectory = directory,
            fileName = CONFIG_FILE_NAME,
            defaultProvider = { snapshot },
            postLoadHook = { _, _, _ -> snapshot },
            forceSaveHook = { _, _ -> true },
        )
    }

    /** `CHAT_SESSION_UPDATE` 首次出现的协议号（Minecraft 1.19.3）。 */
    private const val MIN_SUPPORTED_PROTOCOL: Int = 761

    /** 供测试与诊断使用：当前合并后的表快照。 */
    internal fun snapshotEntries(): List<ChatSessionPacketIdConfig.PacketIdEntry> = mergedEntries

    /** 供测试使用：重置到未加载状态。 */
    internal fun resetForTest() {
        dataDirectory = null
        config = ChatSessionPacketIdConfig()
        mergedEntries = defaultEntries()
        highestKnownProtocol = defaultEntries().maxOfOrNull { it.fromProtocol } ?: 0
    }
}
