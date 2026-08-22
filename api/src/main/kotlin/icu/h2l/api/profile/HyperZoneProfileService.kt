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

import icu.h2l.api.db.Profile
import icu.h2l.api.player.HyperZonePlayer
import java.util.*

/**
 * 核心层 Profile 访问入口。
 *
 * 认证子模块只应通过该服务读取或解析 Profile，
 * 不应再把 Profile 生命周期控制逻辑直接写进 [HyperZonePlayer]。
 */
interface HyperZoneProfileService {
    /**
     * 根据档案标识读取一个正式 [Profile]。
     */
    fun getProfile(profileId: UUID): Profile?

    /**
     * 登录流程中的“resolve”语义必须明确等价于“按既有 profileId 解析已有档案”，
     * 严禁再把“可能创建新档案”混入 resolve 语义。
     */
    fun canResolve(profileId: UUID): Boolean {
        return getProfile(profileId) != null
    }

    /**
     * 按既有 [profileId] 解析一个已存在的 [Profile]。
     *
     * 若指定档案不存在则直接抛错，用于暴露调用方的流程问题。
     */
    fun resolve(profileId: UUID): Profile {
        return getProfile(profileId)
            ?: throw IllegalStateException("未找到 Profile: $profileId")
    }

    /**
     * 读取当前登录态玩家已 attach 的正式档案。
     */
    fun getAttachedProfile(player: HyperZonePlayer): Profile?

    /**
     * 将指定 [profileId] attach 到当前登录态玩家。
     */
    fun attachProfile(player: HyperZonePlayer, profileId: UUID): Profile?

    /**
     * 判断当前登录态玩家是否已经 attach 正式档案。
     */
    fun hasAttachedProfile(player: HyperZonePlayer): Boolean {
        return getAttachedProfile(player) != null
    }

    /**
     * 基于凭证判断是否允许建档。
     *
     * 凭证必须通过 [HyperZoneCredential.getRegistrationName] 提供注册名；
     * 建档时建议 UUID 取自 [HyperZoneCredential.getSuggestedProfileCreateUuid]。
     *
     * @throws IllegalStateException 若凭证未提供注册名
     */
    fun canCreate(credential: HyperZoneCredential): Boolean

    /**
     * 基于凭证新建一个正式 [Profile]。
     *
     * 凭证必须通过 [HyperZoneCredential.getRegistrationName] 提供注册名；
     * 建档时建议 UUID 取自 [HyperZoneCredential.getSuggestedProfileCreateUuid]。
     *
     * @throws IllegalStateException 若凭证未提供注册名，或建档失败
     */
    fun create(credential: HyperZoneCredential): Profile

    /**
     * 根据玩家当前已提交且已验证的凭证，自动解析并 attach 对应档案。
     */
    fun attachVerifiedCredentialProfile(player: HyperZonePlayer): Profile?

    /**
     * 将玩家当前会话已提交的凭证绑定到指定 [profileId]。
     */
    fun bindSubmittedCredentials(player: HyperZonePlayer, profileId: UUID): Profile

    /**
     * 当玩家在外部认证服务（如 Yggdrasil / Mojang）侧改名后，
     * 用认证返回的最新名称同步更新已绑定正式 Profile 的名称。
     *
     * 仅在外部认证名与已绑定 Profile 名不一致时触发实际更新；
     * 名称冲突（已被其他 Profile 占用）时返回 false 并保留旧名，避免破坏登录。
     *
     * 默认实现返回 false，表示当前服务不支持改名同步；
     * 真正的实现由 [icu.h2l.login.profile.VelocityHyperZoneProfileService] 提供。
     *
     * @param profileId 已绑定的正式档案标识
     * @param authenticatedName 外部认证返回的最新名称
     * @return true 表示已同步或本就一致；false 表示同步失败或不受支持
     */
    fun syncProfileName(profileId: UUID, authenticatedName: String): Boolean {
        return false
    }
}

/**
 * [HyperZoneProfileService] 的全局访问器。
 */
object HyperZoneProfileServiceProvider {
    @Volatile
    private var service: HyperZoneProfileService? = null

    /**
     * 绑定当前运行时的 [HyperZoneProfileService] 实例。
     */
    fun bind(service: HyperZoneProfileService) {
        this.service = service
    }

    /**
     * 获取已绑定的 Profile 服务，若尚未初始化则抛错。
     */
    fun get(): HyperZoneProfileService = service ?: error("HyperZone profile service is not available yet")

    /**
     * 获取已绑定的 Profile 服务，若当前不可用则返回 `null`。
     */
    fun getOrNull(): HyperZoneProfileService? = service
}
