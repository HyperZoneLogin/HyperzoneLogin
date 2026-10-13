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

package icu.h2l.api.util

/**
 * 配置文件注释翻译器。
 *
 * 用于将 @Comment 注解中的翻译键（如 "config.core.database"）
 * 解析为当前服务端区域对应的自然语言文本，并在配置文件首次生成时写入。
 */
interface ConfigCommentTranslator {
    /**
     * 将给定的翻译键翻译为本地化文本。
     *
     * @param key 翻译键，格式如 "config.core.database"
     * @return 翻译结果；若键不存在则返回 null（保留原始键）
     */
    fun translate(key: String): String?

    /**
     * 重载翻译资源。
     */
    fun reload()
}

