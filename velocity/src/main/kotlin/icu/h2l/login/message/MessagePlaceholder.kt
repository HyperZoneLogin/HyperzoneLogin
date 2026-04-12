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

package icu.h2l.login.message

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver

sealed interface MessagePlaceholder {
    fun asTagResolver(): TagResolver.Single

    companion object {
        fun text(name: String, value: Any?): MessagePlaceholder {
            return TextMessagePlaceholder(name, value?.toString() ?: "")
        }

        fun component(name: String, value: Component): MessagePlaceholder {
            return ComponentMessagePlaceholder(name, value)
        }
    }
}

private data class TextMessagePlaceholder(
    private val name: String,
    private val value: String
) : MessagePlaceholder {
    override fun asTagResolver(): TagResolver.Single {
        return Placeholder.unparsed(name, value)
    }
}

private data class ComponentMessagePlaceholder(
    private val name: String,
    private val value: Component
) : MessagePlaceholder {
    override fun asTagResolver(): TagResolver.Single {
        return Placeholder.component(name, value)
    }
}

