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

package icu.h2l.api.config

import java.util.concurrent.ConcurrentHashMap

/**
 * [HZLConfig] 的集中注册表。
 *
 * 上层把不同的配置注册到不同的名称下，之后即可通过
 * `HZLConfigRegistry.get(名称)?.get(Comment id)` 访问任意配置项。
 */
object HZLConfigRegistry {

    private val configs = ConcurrentHashMap<String, HZLConfig<*>>()

    /**
     * 以 [name] 注册一个配置访问器。同名会覆盖旧实例。
     */
    fun register(name: String, config: HZLConfig<*>) {
        configs[name] = config
    }

    /**
     * 注销指定名称的配置访问器。
     *
     * @return 被移除的实例；不存在则返回 null。
     */
    fun unregister(name: String): HZLConfig<*>? = configs.remove(name)

    /**
     * 按名称获取配置访问器；不存在返回 null。
     */
    fun get(name: String): HZLConfig<*>? = configs[name]

    /**
     * 重新加载全部已注册配置。
     */
    fun reloadAll() {
        configs.values.forEach { it.reload() }
    }

    /**
     * 一键重新生成全部已注册配置（保留旧值、删除多余键、补齐缺失默认值）。
     *
     * @return 重新生成的配置数量。
     */
    fun regenerateAll(): Int {
        configs.values.forEach { it.regenerate() }
        return configs.size
    }
}
