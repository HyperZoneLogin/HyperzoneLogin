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

package icu.h2l.api.event.profile

import com.velocitypowered.api.event.annotation.AwaitingEvent
import icu.h2l.api.db.Profile
import icu.h2l.api.player.HyperZonePlayer
import java.util.UUID

/**
 * 请求核心层为玩家解析或创建 Profile。
 *
 * 该事件用于把“根据可信身份信息查找档案 / 判断名称冲突 / 必要时创建新档案”的职责
 * 收敛到核心层，避免各认证子模块自行决定 Profile 主键或直接写 Profile 表。
 */
@AwaitingEvent
class ProfileResolveEvent(
    val hyperZonePlayer: HyperZonePlayer,
    val profileIdHint: UUID? = null,
    val trustedName: String? = null,
    val trustedUuid: UUID? = null,
    val allowCreate: Boolean = false
) {
    enum class Status {
        PENDING,
        RESOLVED,
        DENIED
    }

    var profile: Profile? = null
    var created: Boolean = false
    var deniedReason: String? = null
    var status: Status = Status.PENDING
        private set

    val isResolved: Boolean
        get() = status == Status.RESOLVED


    fun resolve(profile: Profile, created: Boolean = false) {
        this.profile = profile
        this.created = created
        deniedReason = null
        status = Status.RESOLVED
    }

    fun deny(reason: String) {
        profile = null
        created = false
        deniedReason = reason
        status = Status.DENIED
    }
}
