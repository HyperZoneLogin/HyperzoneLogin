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

package icu.h2l.login.profile.skin.db

import org.jetbrains.exposed.sql.Table

class ProfileSkinCacheTable(prefix: String) : Table("${prefix}skin_cache") {
    val id = uuid("id")
    val sourceHash = varchar("source_hash", 64).nullable()
    val sourceCacheEligible = bool("source_cache_eligible").nullable()
    val skinUrl = varchar("skin_url", 1024).nullable()
    val skinModel = varchar("skin_model", 16).nullable()
    val textureValue = text("texture_value")
    val textureSignature = text("texture_signature").nullable()
    val updatedAt = long("updated_at")

    init {
        index(false, sourceHash)
    }

    override val primaryKey = PrimaryKey(id)
}


