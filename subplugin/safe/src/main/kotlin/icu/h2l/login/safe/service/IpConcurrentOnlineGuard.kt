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

package icu.h2l.login.safe.service

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 同 IP 同时在线会话限制。
 *
 * 与 [ConnectionRateLimiter]（限制“频率”，窗口内允许多少次尝试）不同，
 * 这里限制的是“并发”，即同一时刻同一 IP 允许同时存在的登录/在线会话数。
 * 用于对抗脚本机器人：大量账号共享一个出口 IP 时，即使每个账号登录频率都不高，
 * 也会占满并发限额。
 *
 * 设计约定：
 * - 槽位按 [channelId]（连接粒度）占用与释放，这样同一 IP 的多个连接可互相独立释放；
 * - [release] 是幂等的：对一个未占用的 [channelId] 调用不会造成重复释放；
 * - 计数用 [AtomicInteger] 保证跨事件循环线程的原子性；
 * - [maxConcurrent] 为 0 表示不限制。
 */
class IpConcurrentOnlineGuard(
    private val enabled: Boolean,
    private val maxConcurrent: Int
) {
    /** 每个连接占用的并发槽位记录：channelId -> IP。 */
    private val reservationByChannel = ConcurrentHashMap<String, String>()

    /** 每个 IP 当前的并发槽位计数。 */
    private val countByIp = ConcurrentHashMap<String, AtomicInteger>()

    /**
     * 尝试为指定 IP 占用一个并发槽位。
     *
     * @param ip 玩家来源 IP
     * @param channelId 当前连接的唯一标识（如 Netty channel id），用于断开时释放
     * @return 是否占用成功。`false` 表示该 IP 已达到 [maxConcurrent] 上限，连接应被拒绝。
     */
    fun tryReserve(ip: String, channelId: String): Boolean {
        if (!enabled || maxConcurrent <= 0) {
            return true
        }
        val counter = countByIp.computeIfAbsent(ip) { AtomicInteger(0) }
        // 原子地“加一后判断是否超限”；超限则回退本次加一，避免占用后又被拒绝导致计数泄漏。
        val current = counter.incrementAndGet()
        if (current > maxConcurrent) {
            counter.decrementAndGet()
            return false
        }
        reservationByChannel[channelId] = ip
        return true
    }

    /**
     * 释放某个连接占用的并发槽位。
     *
     * 幂等：对同一 [channelId] 重复调用不会造成重复释放。
     */
    fun release(channelId: String) {
        if (!enabled) {
            return
        }
        val ip = reservationByChannel.remove(channelId) ?: return
        val counter = countByIp[ip] ?: return
        counter.decrementAndGet()
        // 计数回零时清理，避免长期运行后内存中残留大量空键。
        if (counter.get() <= 0) {
            countByIp.remove(ip, counter)
        }
    }

    /** 当前 IP 已占用的并发槽位数，供日志或监控使用。 */
    fun currentCount(ip: String): Int = countByIp[ip]?.get() ?: 0
}
