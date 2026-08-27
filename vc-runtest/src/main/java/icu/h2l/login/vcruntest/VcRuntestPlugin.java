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

package icu.h2l.login.vcruntest;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import icu.h2l.api.HyperZoneApi;
import icu.h2l.api.HyperZoneApiProvider;
import icu.h2l.api.command.HyperChatCommandManager;
import icu.h2l.api.db.HyperZoneDatabaseManager;
import icu.h2l.api.module.HyperSubModule;
import icu.h2l.api.player.HyperZonePlayerAccessor;
import icu.h2l.api.profile.CredentialChannelRegistry;
import icu.h2l.api.vServer.HyperZoneVServerAdapter;
import icu.h2l.login.HyperZoneLoginMain;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Stable bridge plugin for vc-runtest.
 *
 * This plugin is the Velocity-registered object, so Velocity's EventManager
 * accepts it as a plugin container for event listener registration.  It directly
 * implements {@link HyperZoneApi} and boots {@link HyperZoneLoginMain} from the
 * Gradle runtimeClasspath (no jar copying, no network download).
 */
public final class VcRuntestPlugin implements HyperZoneApi {
    private final ProxyServer velocityProxy;
    private final ComponentLogger logger;
    private final Path runtimeDataDirectory;

    private HyperZoneLoginMain runtime;

    @Inject
    public VcRuntestPlugin(
        ProxyServer proxy,
        ComponentLogger logger,
        @DataDirectory Path dataDirectory
    ) {
        this.velocityProxy = proxy;
        this.logger = logger;
        // Put HZL data next to the vc-runtest data dir so configs look the same
        // as a real install (plugins/hyperzonelogin/).
        this.runtimeDataDirectory = dataDirectory.resolveSibling("hyperzonelogin");
    }

    @Subscribe
    public void onEnable(ProxyInitializeEvent event) throws IOException {
        Files.createDirectories(this.runtimeDataDirectory);
        // Bind THIS (a real Velocity plugin instance) as the HyperZoneApi so that
        // all event.register(api, listener) calls work because Velocity knows about us.
        HyperZoneApiProvider.INSTANCE.bind(this);
        this.runtime = new HyperZoneLoginMain(
            this.velocityProxy,
            this.logger,
            this.runtimeDataDirectory,
            this
        );
        this.runtime.onEnable(event);
        this.logger.info("vc-runtest bridge: HyperZoneLogin started from Gradle classpath");
    }

    // ── HyperZoneApi ──────────────────────────────────────────────────────────

    @Override
    public ProxyServer getProxy() {
        return this.velocityProxy;
    }

    @Override
    public Path getDataDirectory() {
        return this.runtimeDataDirectory;
    }

    @Override
    public HyperZoneDatabaseManager getDatabaseManager() {
        return this.runtime.getDatabaseManager();
    }

    @Override
    public HyperZonePlayerAccessor getHyperZonePlayers() {
        return this.runtime.getHyperZonePlayers();
    }

    @Override
    public HyperChatCommandManager getChatCommandManager() {
        return this.runtime.getChatCommandManager();
    }

    @Override
    public HyperZoneVServerAdapter getServerAdapter() {
        return this.runtime.getServerAdapter();
    }

    @Override
    public CredentialChannelRegistry getCredentialChannelRegistry() {
        return this.runtime.getCredentialChannelRegistry();
    }

    @Override
    public void registerModule(HyperSubModule module) {
        this.runtime.registerModule(module, this);
    }
}
