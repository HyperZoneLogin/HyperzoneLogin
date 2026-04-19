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

package icu.h2l.login.vServer.backend.compat

import com.velocitypowered.api.event.Subscribe
import icu.h2l.api.event.connection.OpenStartAuthEvent
import icu.h2l.api.event.profile.VerifyInitialGameProfileEvent
import icu.h2l.api.util.RemapUtils
import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.manager.HyperZonePlayerManager

/**
 * backend 模式专用：
 * - 在 OpenStartAuth 阶段把玩家切到可信临时档案；
 * - 在 GameProfileRequest 阶段校验该临时档案没有被其它插件篡改。
 *
 * outpre 全链路由自身桥接控制，不再复用这层兼容逻辑。
 */
class BackendProfileLayerCompatListener {
    companion object {
        private const val EXPECTED_NAME_PREFIX = RemapUtils.EXPECTED_NAME_PREFIX
        private const val REMAP_PREFIX = RemapUtils.REMAP_PREFIX
        const val PLUGIN_CONFLICT_MESSAGE = "登录失败：检测到插件冲突。"
    }

    @Subscribe
    fun onStartAuth(event: OpenStartAuthEvent) {
        if (!isEnabled()) return

        event.gameProfile = HyperZonePlayerManager.getByChannel(event.channel).getTemporaryGameProfile()
    }

    @Subscribe
    fun onVerifyInitialGameProfileEvent(event: VerifyInitialGameProfileEvent) {
        if (!isEnabled()) return

        val incomingProfile = event.gameProfile
        val incomingName = incomingProfile.name

        if (!incomingName.startsWith(EXPECTED_NAME_PREFIX)) return
        val expectedUuid = RemapUtils.genUUID(incomingName, REMAP_PREFIX)
        if (incomingProfile.id != expectedUuid) return

        event.pass = true
    }

    private fun isEnabled(): Boolean {
        return HyperZoneLoginMain.getInstance().serverAdapter?.needsBackendInitialProfileCompat() == true
    }
}
