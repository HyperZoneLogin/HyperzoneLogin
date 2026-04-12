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

package icu.h2l.login.merge

import com.velocitypowered.api.command.CommandSource
import icu.h2l.api.message.HyperZoneMessagePlaceholder
import icu.h2l.api.message.HyperZoneMessageServiceProvider
import net.kyori.adventure.text.Component

object MergeMessages {
    private const val NAMESPACE = "data-merge"

    fun usageMl(source: CommandSource): Component = render(source, "usage.ml", "/hzl-merge ml")
    fun usageAm(source: CommandSource): Component = render(source, "usage.am", "/hzl-merge am")
    fun alreadyRunning(source: CommandSource): Component = render(source, "already-running", "迁移正在执行中，请稍后再试")
    fun startMl(source: CommandSource): Component = render(source, "start.ml", "开始执行 ML 迁移，请稍候...")
    fun startAm(source: CommandSource): Component = render(source, "start.am", "开始执行 AUTHME 迁移，请稍候...")

    fun completed(source: CommandSource, summary: String): Component {
        return render(source, "completed", "迁移完成: <summary>", HyperZoneMessagePlaceholder.text("summary", summary))
    }

    fun detailLog(source: CommandSource, fileName: String): Component {
        return render(source, "detail-log", "详细日志已输出到 <file_name>", HyperZoneMessagePlaceholder.text("file_name", fileName))
    }

    fun failed(source: CommandSource, reason: String?): Component {
        return render(
            source,
            "failed",
            "迁移失败: <reason>",
            HyperZoneMessagePlaceholder.text("reason", reason ?: "未知错误")
        )
    }

    private fun render(
        source: CommandSource,
        key: String,
        fallback: String,
        vararg placeholders: HyperZoneMessagePlaceholder
    ): Component {
        val service = HyperZoneMessageServiceProvider.getOrNull()
        return service?.render(source, "$NAMESPACE.$key", *placeholders)
            ?: Component.text(placeholders.fold(fallback) { acc, placeholder ->
                val replacement = when (placeholder) {
                    is HyperZoneMessagePlaceholder.Text -> placeholder.value
                    is HyperZoneMessagePlaceholder.ComponentValue -> placeholder.value.toString()
                }
                acc.replace("<${placeholder.name}>", replacement)
            })
    }
}

