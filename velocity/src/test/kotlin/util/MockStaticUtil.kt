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

package util

import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.config.CoreConfig

internal object MockStaticUtil {
    private val instanceField = staticField("instance")
    private val coreConfigField = staticField("coreConfig")

    fun setCoreConfig(value: CoreConfig) {
        coreConfigField.set(null, value)
    }

    fun clear() {
        instanceField.set(null, null)
        coreConfigField.set(null, null)
    }

    private fun staticField(name: String) = HyperZoneLoginMain::class.java.getDeclaredField(name).apply {
        isAccessible = true
    }
}
