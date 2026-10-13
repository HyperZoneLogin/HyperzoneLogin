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
 * [ConfigCommentTranslator] 的全局访问器。
 *
 * 在插件初始化阶段（任何配置文件加载之前）由主插件注册翻译器实例，
 * 供 [ConfigLoader] 在保存配置文件时自动将翻译键替换为本地化注释。
 */
object ConfigCommentTranslatorProvider {

    @Volatile
    private var translator: ConfigCommentTranslator? = null

    /**
     * 绑定翻译器实例（应在任何配置加载前调用）。
     */
    fun bind(translator: ConfigCommentTranslator) {
        this.translator = translator
    }

    /**
     * 获取当前翻译器，若未注册则返回 null。
     */
    fun getOrNull(): ConfigCommentTranslator? = translator

    /**
     * 清除翻译器（通常在插件卸载时调用）。
     */
    fun clear() {
        translator = null
    }
}
