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

import icu.h2l.api.util.IPropertiesHolder
import java.util.*

/**
 * 认证子模块向登录核心提交的可信凭证。
 *
 * 注意：凭证对象本身不直接携带 userUUID 字段；
 * 是否已绑定、如何绑定到 User，都由各认证模块自己的实现负责。
 *
 * 该对象只负责证明认证结果与渠道账号绑定，不携带、不决定 InGameProfile；
 * 游戏内身份由核心根据凭证中的认证名称/UUID 自行推导，外部模块不得提交或写入 InGameProfile。
 */
interface HyperZoneCredential : IPropertiesHolder {
    /**
     * 认证渠道唯一标识，可用于区分不同上游。
     */
    val channelId: String

    /**
     * 该渠道内部可识别的凭证标识。
     */
    val credentialId: String

    /**
     * 读取该凭证当前已经绑定到的 User。
     *
     * 返回 null 表示该凭证尚未完成绑定。
     */
    fun getBoundUserUUID(): UUID?

    /**
     * 当该凭证尚未绑定 User，但后续可能通过显式 create 流程建档时，
     * 返回其建议使用的档案 UUID；返回 null 表示 create 阶段不应透传 UUID。
     */
    fun getSuggestedGameUuid(): UUID? {
        return null
    }

    /**
     * 返回该凭证对应的注册名（即建档时应使用的玩家名称）。
     *
     * 实现类应覆盖此方法以提供注册名，使得核心层可以通过凭证完成建档操作，
     * 而无需调用方裸露地传递玩家名称字符串。
     *
     * 返回 null 表示该凭证不携带注册名，此时凭证不能直接用于 [HyperZoneUserService.canCreate] /
     * [HyperZoneUserService.create] 的凭证重载。
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
     * 在真正写入绑定关系前做一次校验。
     *
     * 返回 null 表示允许绑定；否则返回拒绝原因。
     *
     * @param userUUID 待绑定的正式档案标识
     */
    fun validateBind(userUUID: UUID): String? {
        return null
    }

    /**
     * 将该凭证绑定到指定 User。
     *
     * 实现应把绑定关系写入模块自己的数据表。
     *
     * @param userUUID 要绑定到的正式档案标识
     */
    fun bind(userUUID: UUID): Boolean

    /**
     * 解除当前渠道账号与该 User 的绑定关系。
     *
     * ============================================================
     * 危险操作边界（后续实现者必须严格遵守）
     * ============================================================
     *
     * 该接口只允许删除“当前渠道账号 -> User”的绑定记录，例如：
     *
     * - Floodgate：删除当前 XUID 对应的 `floodgate_auth` 行；
     * - Yggdrasil：删除当前入口 + UUID 对应的 `entry_*` 行；
     * - Offline：删除当前离线认证名称对应的 `offline_auth` 行。
     *
     * 严禁在该接口内：
     *
     * - 创建、合并或删除 HZL User；
     * - 创建、删除、切换或修改 InGameProfile；
     * - 解绑后自动重新绑定、自动建档、自动 attach 或触发其他登录补偿流程；
     * - 修改其他渠道的账号绑定。
     *
     * 该接口的语义只有一个：切断当前渠道账号。调用方在成功后必须立即踢出玩家，
     * 不允许在同一会话里继续走任何建档/绑定/身份补偿分支；否则会立刻产生
     * “渠道账号被删除后又以新的 HZL User 重建”的档案冲突。
     *
     * 未实现该能力的渠道必须抛 [UnsupportedOperationException]，不得静默返回 false。
     *
     * @return true 表示当前渠道绑定已删除；false 表示绑定不存在或删除失败
     */
    fun unbind(userUUID: UUID): Boolean {
        throw UnsupportedOperationException(
            "Credential ${channelId}:${credentialId} does not support unbind"
        )
    }

    /**
     * 核心层确认凭证消费完成（通常是绑定并 attach 成功）后的回调。
     *
     * 默认空实现；渠道可按需落库或清理一次性状态。
     */
    fun onConsumed(userUUID: UUID) {
        // no-op by default
    }
}
