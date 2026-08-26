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

package icu.h2l.api.profile

import java.util.*

/**
 * 认证子模块向登录核心提交的可信凭证。
 *
 * 注意：凭证对象本身不直接携带 profileId 字段；
 * 是否已绑定、如何绑定到 Profile，都由各认证模块自己的实现负责。
 */
interface HyperZoneCredential {
    /**
     * 认证渠道唯一标识，由子模块负责稳定定义。
     */
    val channelId: String

    /**
     * 该渠道内部可识别的凭证标识。
     */
    val credentialId: String

    /**
     * 读取该凭证当前已经绑定到的 Profile。
     *
     * 返回 null 表示该凭证尚未完成绑定。
     */
    fun getBoundProfileId(): UUID?

    /**
     * 当该凭证尚未绑定 Profile，但后续可能通过显式 create 流程建档时，
     * 返回其建议使用的档案 UUID；返回 null 表示 create 阶段不应透传 UUID。
     */
    fun getSuggestedProfileCreateUuid(): UUID? {
        return null
    }

    /**
     * 返回该凭证对应的注册名（即建档时应使用的玩家名称）。
     *
     * 实现类应覆盖此方法以提供注册名，使得核心层可以通过凭证完成建档操作，
     * 而无需调用方裸露地传递玩家名称字符串。
     *
     * 返回 null 表示该凭证不携带注册名，此时凭证不能直接用于 [HyperZoneProfileService.canCreate] /
     * [HyperZoneProfileService.create] 的凭证重载。
     */
    fun getRegistrationName(): String? {
        return null
    }

    /**
     * 返回一个应用了注册名变更的新凭证实例。
     *
     * 默认返回 null，表示该渠道不支持 rename。
     */
    fun withRegistrationName(newRegistrationName: String): HyperZoneCredential? {
        return null
    }

    /**
     * 返回一个用于 reUUID 流程的新凭证实例。
     *
     * 默认返回 null，表示该渠道不支持 reUUID。
     */
    fun withReUuidRequest(): HyperZoneCredential? {
        return null
    }

    /**
     * 在真正写入绑定关系前做一次校验。
     *
     * 返回 null 表示允许绑定；否则返回拒绝原因。
     *
     * @param profileId 待绑定的正式档案标识
     */
    fun validateBind(profileId: UUID): String? {
        return null
    }

    /**
     * 将该凭证绑定到指定 Profile。
     *
     * 实现应把绑定关系写入模块自己的数据表。
     *
     * @param profileId 要绑定到的正式档案标识
     */
    fun bind(profileId: UUID): Boolean

    /**
     * 核心层确认凭证消费完成（通常是绑定并 attach 成功）后的回调。
     *
     * 默认空实现；渠道可按需落库或清理一次性状态。
     */
    fun onConsumed(profileId: UUID) {
        // no-op by default
    }
}
