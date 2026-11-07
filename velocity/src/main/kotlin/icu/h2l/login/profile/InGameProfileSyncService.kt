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

package icu.h2l.login.profile

import com.velocitypowered.api.proxy.ProxyServer
import icu.h2l.api.db.InGameProfileCandidate
import icu.h2l.api.event.profile.InGameProfileLookupEvent
import icu.h2l.api.profile.InGameProfileConflictException
import java.util.*

/**
 * InGameProfile 反向同步服务。
 *
 * 通过 [InGameProfileLookupEvent] 收集各渠道按 userUUID 反查到的记录，
 * 再交由核心统一做冲突检查与落库。
 */
class InGameProfileSyncService(
    private val proxy: ProxyServer,
    private val userService: VelocityHyperZoneUserService,
) {
    data class Conflict(
        val candidate: InGameProfileCandidate,
        val reason: String,
    )

    data class Result(
        val scanned: Int,
        val added: Int,
        val existing: Int,
        val conflicts: List<Conflict>,
    )

    fun addMissingInGameProfiles(userUUID: UUID): Result {
        userService.getUser(userUUID)
            ?: throw IllegalStateException("未找到 User: $userUUID")

        val event = InGameProfileLookupEvent(userUUID)
        proxy.eventManager.fire(event).join()

        val candidates = event.candidates()
            .map { candidate ->
                candidate to (candidate.uuid ?: userService.defaultGameUuid(candidate.name.trim()))
            }
            .distinctBy { (candidate, uuid) ->
                "$uuid|${candidate.name.trim().lowercase(Locale.ROOT)}"
            }

        var added = 0
        var existing = 0
        val conflicts = mutableListOf<Conflict>()

        candidates.forEach { (candidate, uuid) ->
            try {
                val current = userService.findInGameProfileByUuid(uuid)
                if (current != null) {
                    if (current.userUUID == userUUID) {
                        existing++
                    } else {
                        conflicts += Conflict(
                            candidate,
                            "UUID $uuid 已属于其他 User: ${current.userUUID}",
                        )
                    }
                } else {
                    userService.createInGameProfile(userUUID, candidate.copy(uuid = uuid))
                    added++
                }
            } catch (throwable: InGameProfileConflictException) {
                conflicts += Conflict(candidate, throwable.conflictReason)
            } catch (throwable: IllegalStateException) {
                conflicts += Conflict(candidate, throwable.message ?: "同步失败")
            }
        }

        return Result(
            scanned = candidates.size,
            added = added,
            existing = existing,
            conflicts = conflicts,
        )
    }
}
