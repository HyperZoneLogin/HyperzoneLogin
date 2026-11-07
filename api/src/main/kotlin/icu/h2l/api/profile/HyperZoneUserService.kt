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

import icu.h2l.api.db.InGameProfile
import icu.h2l.api.db.InGameProfileCandidate
import icu.h2l.api.db.User
import icu.h2l.api.player.HyperZonePlayer
import java.util.*

/**
 * 核心层 User 访问入口。
 *
 * 认证子模块只应通过该服务读取或解析 User，
 * 不应再把 User 生命周期控制逻辑直接写进 [HyperZonePlayer]。
 */
interface HyperZoneUserService {
    /**
     * 根据档案标识读取一个正式 [User]。
     */
    fun getUser(userUUID: UUID): User?

    /**
     * 根据玩家正式游戏名读取一个正式 [User]。
     */
    fun getUserByName(name: String): User?

    /**
     * 根据玩家正式游戏 UUID 读取一个正式 [User]。
     */
    fun getUserByGameUuid(playerUuid: UUID): User?

    /**
     * 登录流程中的“resolve”语义必须明确等价于“按既有 userUUID 解析已有档案”，
     * 严禁再把“可能创建新档案”混入 resolve 语义。
     */
    fun canResolveUser(userUUID: UUID): Boolean {
        return getUser(userUUID) != null
    }

    /**
     * 按既有 [userUUID] 解析一个已存在的 [User]。
     *
     * 若指定档案不存在则直接抛错，用于暴露调用方的流程问题。
     */
    fun resolveUser(userUUID: UUID): User {
        return getUser(userUUID)
            ?: throw IllegalStateException("未找到 User: $userUUID")
    }

    /**
     * 读取当前登录态玩家已 attach 的正式档案。
     */
    fun getAttachedUser(player: HyperZonePlayer): User?

    /**
     * 将指定 [userUUID] attach 到当前登录态玩家。
     *
     * 该接口只负责 HZL User 的挂载与事件触发；
     * 不读取凭证、不选择、不写入 InGameProfile。
     */
    fun attachUser(player: HyperZonePlayer, userUUID: UUID): User?

    /**
     * 判断当前登录态玩家是否已经 attach 正式档案。
     */
    fun hasAttachedUser(player: HyperZonePlayer): Boolean {
        return getAttachedUser(player) != null
    }

    /**
     * 基于凭证判断是否允许建档。
     *
     * 凭证必须通过 [HyperZoneCredential.getRegistrationName] 提供注册名；
     * 建档时建议 UUID 取自 [HyperZoneCredential.getSuggestedGameUuid]。
     *
     * @throws IllegalStateException 若凭证未提供注册名
     */
    fun canCreateUser(credential: HyperZoneCredential): Boolean

    /**
     * 基于凭证新建一个正式 [User]。
     *
     * 凭证必须通过 [HyperZoneCredential.getRegistrationName] 提供注册名；
     * 建档时建议 UUID 取自 [HyperZoneCredential.getSuggestedGameUuid]。
     *
     * @throws IllegalStateException 若凭证未提供注册名，或建档失败
     */
    fun createUser(credential: HyperZoneCredential): User

    /**
     * 根据玩家当前已提交且已验证的凭证，解析对应的 HZL User。
     *
     * 该接口只读取凭证的 HZL User 绑定结果，不 attach、不读取/写入 InGameProfile。
     * 返回 null 表示凭证尚未绑定任何 HZL User。
     */
    fun resolveAuthenticatedUser(player: HyperZonePlayer): User?

    /**
     * 将玩家当前会话已提交的凭证绑定到指定 [userUUID]。
     *
     * 该接口只负责渠道账号与 HZL User 的绑定关系，不写入 InGameProfile。
     */
    fun bindSubmittedCredentials(player: HyperZonePlayer, userUUID: UUID): User

    /**
     * 读取指定账号下的全部游戏内身份。
     */
    fun getInGameProfiles(userUUID: UUID): List<InGameProfile> {
        throw UnsupportedOperationException("HyperZoneUserService.getInGameProfiles is not supported")
    }

    /**
     * 按记录 ID 读取一条游戏内身份。
     */
    fun getInGameProfile(inGameProfileId: UUID): InGameProfile? {
        throw UnsupportedOperationException("HyperZoneUserService.getInGameProfile is not supported")
    }

    /**
     * 读取指定账号当前活动的游戏内身份。
     */
    fun getActiveInGameProfile(userUUID: UUID): InGameProfile? {
        throw UnsupportedOperationException("HyperZoneUserService.getActiveInGameProfile is not supported")
    }

    /**
     * 按名称生成核心默认游戏 UUID。
     */
    fun defaultGameUuid(name: String): UUID {
        throw UnsupportedOperationException("HyperZoneUserService.defaultGameUuid is not supported")
    }

    /**
     * 检查一条渠道游戏内身份是否可以写入目标账号。
     *
     * @param userUUID 目标账号；null 表示新建账号
     * @return null 表示允许；否则返回冲突原因
     */
    fun validateInGameProfileCandidate(userUUID: UUID?, candidate: InGameProfileCandidate): String? {
        throw UnsupportedOperationException("HyperZoneUserService.validateInGameProfileCandidate is not supported")
    }

    /**
     * 按游戏内 UUID 查找一条已存在的记录。
     *
     * 该接口只接受明确 UUID，不接受候选对象，也不负责生成默认 UUID。
     * UUID 全局唯一，因此查询不限定 User；调用方需自行校验记录所属账号。
     * 该方法只读，不写库。
     */
    fun findInGameProfileByUuid(uuid: UUID): InGameProfile? {
        throw UnsupportedOperationException("HyperZoneUserService.findInGameProfileByUuid is not supported")
    }

    /**
     * 新增一条游戏内身份。
     *
     * 该接口只允许插入；若同一 UUID 已存在（无论属于哪个 User），必须抛错，
     * 不允许隐式更新或复用。名称不得与其他 User 冲突。
     * 冲突时必须抛出 [InGameProfileConflictException]。
     * 若目标账号当前没有活动身份，新记录会被设为活动身份。
     */
    fun createInGameProfile(userUUID: UUID, candidate: InGameProfileCandidate): InGameProfile {
        throw UnsupportedOperationException("HyperZoneUserService.createInGameProfile is not supported")
    }

    /**
     * 切换指定账号当前活动的游戏内身份。
     *
     * 返回切换后的身份；记录不存在或不属于该账号时必须抛错。
     */
    fun switchActiveInGameProfile(userUUID: UUID, inGameProfileId: UUID): InGameProfile {
        throw UnsupportedOperationException("HyperZoneUserService.switchActiveInGameProfile is not supported")
    }
}

/**
 * [HyperZoneUserService] 的全局访问器。
 */
object HyperZoneUserServiceProvider {
    @Volatile
    private var service: HyperZoneUserService? = null

    /**
     * 绑定当前运行时的 [HyperZoneUserService] 实例。
     */
    fun bind(service: HyperZoneUserService) {
        this.service = service
    }

    /**
     * 获取已绑定的 User 服务，若尚未初始化则抛错。
     */
    fun get(): HyperZoneUserService = service ?: error("HyperZone user service is not available yet")

    /**
     * 获取已绑定的 User 服务，若当前不可用则返回 `null`。
     */
    fun getOrNull(): HyperZoneUserService? = service
}
