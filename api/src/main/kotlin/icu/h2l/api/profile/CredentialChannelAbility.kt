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

/**
 * 凭证渠道能力描述。
 *
 * 当凭证渠道通过 [CredentialChannelRegistry.registerChannel] 注册时，
 * 核心层会根据当前配置计算出该渠道的能力集合并返回。
 * 子模块可据此决定在各自业务流程中应当开放还是禁止特定操作。
 *
 * @property channelId   对应的凭证渠道唯一标识
 * @property canRegister 该渠道是否允许通过凭证注册（建档）新玩家档案。
 *                       若为 `false`，子模块不应调用 [HyperZoneUserService.create] 等新建档案接口。
 */
data class CredentialChannelAbility(
    val channelId: String,
    val canRegister: Boolean,
)

