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

package icu.h2l.login.auth.offline

import icu.h2l.api.HyperZoneApi
import icu.h2l.api.db.HyperZoneDatabaseManager
import icu.h2l.api.db.table.ProfileTable
import icu.h2l.api.log.info
import icu.h2l.api.module.HyperSubModule
import icu.h2l.api.profile.HyperZoneProfileServiceProvider
import icu.h2l.login.auth.offline.command.OfflineAuthCommandRegistrar
import icu.h2l.login.auth.offline.config.OfflineAuthConfigLoader
import icu.h2l.login.auth.offline.config.OfflineAuthMessageResourceLoader
import icu.h2l.login.auth.offline.db.OfflineAuthRepository
import icu.h2l.login.auth.offline.db.OfflineAuthTableManager
import icu.h2l.login.auth.offline.mail.JakartaMailOfflineAuthEmailSender
import icu.h2l.login.auth.offline.mail.LoggingOfflineAuthEmailSender
import icu.h2l.login.auth.offline.mail.OfflineAuthEmailSender
import icu.h2l.login.auth.offline.service.OfflineAuthService
import icu.h2l.login.auth.offline.service.PendingOfflineRegistrationManager
import icu.h2l.login.auth.offline.config.OfflineMatchConfigLoader
import icu.h2l.login.auth.offline.listener.OfflinePreLoginListener
import icu.h2l.login.auth.offline.listener.OfflineSessionAuthListener
import icu.h2l.login.auth.offline.totp.OfflineTotpAuthenticator
import java.util.Locale

class OfflineSubModule : HyperSubModule {
    lateinit var offlineAuthTableManager: OfflineAuthTableManager
    lateinit var offlineAuthRepository: OfflineAuthRepository
    lateinit var offlineAuthService: OfflineAuthService

    override fun register(api: HyperZoneApi) {
        val proxy = api.proxy
        val dataDirectory = api.dataDirectory
        val databaseManager: HyperZoneDatabaseManager = api.databaseManager

        val profileTable = ProfileTable(databaseManager.tablePrefix)
        // Load offline matching configuration for this module
        OfflineMatchConfigLoader.load(dataDirectory)
        OfflineAuthConfigLoader.load(dataDirectory)
        OfflineAuthMessageResourceLoader.load(dataDirectory)
        offlineAuthTableManager = OfflineAuthTableManager(
            databaseManager = databaseManager,
            tablePrefix = databaseManager.tablePrefix,
            profileTable = profileTable
        )
        offlineAuthRepository = OfflineAuthRepository(
            databaseManager = databaseManager,
            table = offlineAuthTableManager.offlineAuthTable
        )
        val offlineAuthConfig = OfflineAuthConfigLoader.getConfig()
        val logger = java.util.logging.Logger.getLogger("hzl-auth-offline")
        val emailSender: OfflineAuthEmailSender = when (offlineAuthConfig.email.deliveryMode.uppercase(Locale.ROOT)) {
            "SMTP" -> JakartaMailOfflineAuthEmailSender(
                config = offlineAuthConfig.email.smtp,
                serverName = offlineAuthConfig.email.smtp.serverName,
                logger = logger
            )

            else -> LoggingOfflineAuthEmailSender(logger, offlineAuthConfig.email.deliveryMode.uppercase(Locale.ROOT))
        }
        val totpAuthenticator = OfflineTotpAuthenticator(
            issuer = offlineAuthConfig.totp.issuer,
            pendingExpireMinutes = offlineAuthConfig.totp.pendingExpireMinutes
        )
        val pendingRegistrations = PendingOfflineRegistrationManager()
        offlineAuthService = OfflineAuthService(
            repository = offlineAuthRepository,
            pendingRegistrations = pendingRegistrations,
            playerAccessor = api.hyperZonePlayers,
            profileService = HyperZoneProfileServiceProvider.get(),
            emailSender = emailSender,
            totpAuthenticator = totpAuthenticator,
            proxy = proxy
        )
        offlineAuthTableManager.createTable()
        proxy.eventManager.register(api, offlineAuthTableManager)

        // Register pre-login listener (handles channel init + offline UUID matching)
        proxy.eventManager.register(api, OfflinePreLoginListener())
        proxy.eventManager.register(api, OfflineSessionAuthListener(offlineAuthService))

        OfflineAuthCommandRegistrar.registerAll(
            commandManager = api.chatCommandManager,
            authService = offlineAuthService
        )
        proxy.eventManager.register(api, OfflineLimboEventListener(offlineAuthService))
        info { "OfflineSubModule 已加载，离线聊天命令与提示监听器已注册" }
    }
}