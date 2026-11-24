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

package icu.h2l.login.vServer.backend

import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.RegisteredServer
import icu.h2l.login.config.CoreConfig
import icu.h2l.login.config.VServerConfig
import util.MockStaticUtil
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Optional

class BackendAuthHoldListenerTest {
    private val authServer = mockk<RegisteredServer>()
    private lateinit var proxyServer: ProxyServer
    private lateinit var listener: BackendAuthHoldListener

    @BeforeEach
    fun setUp() {
        proxyServer = mockk()
        every { proxyServer.getServer("auth") } returns Optional.of(authServer)
        MockStaticUtil.setCoreConfig(backendCoreConfig(fallbackAuthServer = "auth"))
        listener = BackendAuthHoldListener(proxyServer)
    }

    @AfterEach
    fun cleanup() {
        MockStaticUtil.clear()
    }

    @Test
    fun `getRegisteredVServer returns the configured auth server`() {
        assertSame(authServer, listener.getRegisteredVServer())
    }

    @Test
    fun `getRegisteredVServer returns null when no auth server is configured`() {
        MockStaticUtil.setCoreConfig(backendCoreConfig(fallbackAuthServer = ""))

        assertNull(listener.getRegisteredVServer())
    }

    @Test
    fun `getRegisteredVServer returns null when the auth server is not registered`() {
        every { proxyServer.getServer("auth") } returns Optional.empty()

        assertNull(listener.getRegisteredVServer())
    }

    private fun backendCoreConfig(fallbackAuthServer: String): CoreConfig {
        return CoreConfig(
            vServer = VServerConfig(
                mode = "backend",
                backend = VServerConfig.BackendConfig(fallbackAuthServer = fallbackAuthServer),
            )
        )
    }
}
