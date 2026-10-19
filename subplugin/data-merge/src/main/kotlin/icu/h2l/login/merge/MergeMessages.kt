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

    fun usageMl(source: CommandSource): Component = render(source, "usage.ml")
    fun usageAm(source: CommandSource): Component = render(source, "usage.am")
    fun alreadyRunning(source: CommandSource): Component = render(source, "already-running")
    fun startMl(source: CommandSource): Component = render(source, "start.ml")
    fun startAm(source: CommandSource): Component = render(source, "start.am")

    fun completed(source: CommandSource, summary: String): Component {
        return render(source, "completed", HyperZoneMessagePlaceholder.text("summary", summary))
    }

    fun detailLog(source: CommandSource, fileName: String): Component {
        return render(source, "detail-log", HyperZoneMessagePlaceholder.text("file_name", fileName))
    }

    fun failed(source: CommandSource, reason: String?): Component {
        return render(source, "failed",
            HyperZoneMessagePlaceholder.text("reason", reason ?: "未知错误")
        )
    }

    private fun render(
        source: CommandSource,
        key: String,
        vararg placeholders: HyperZoneMessagePlaceholder
    ): Component {
        return HyperZoneMessageServiceProvider.get().render(source, "$NAMESPACE.$key", *placeholders)
    }
}

