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

package icu.h2l.login;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import icu.h2l.api.HyperZoneApi;
import icu.h2l.api.HyperZoneApiProvider;
import icu.h2l.api.command.HyperChatCommandManager;
import icu.h2l.api.db.HyperZoneDatabaseManager;
import icu.h2l.api.dependency.HyperDependency;
import icu.h2l.api.dependency.HyperDependencyManifest;
import icu.h2l.api.dependency.HyperDependencyManager;
import icu.h2l.api.dependency.HyperDependencyProgressListener;
import icu.h2l.api.dependency.HyperDependencyRepository;
import icu.h2l.api.dependency.VelocityHyperDependencyClassPathAppender;
import icu.h2l.api.module.HyperSubModule;
import icu.h2l.api.player.HyperZonePlayerAccessor;
import icu.h2l.api.vServer.HyperZoneVServerAdapter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

public final class HyperZoneLoginBootstrap implements HyperZoneApi {
    private final ProxyServer proxy;
    private final ComponentLogger logger;
    private final Path dataDirectory;
    private final HyperZoneLoginMain runtime;

    @Inject
    public HyperZoneLoginBootstrap(
        ProxyServer proxy,
        ComponentLogger logger,
        @DataDirectory Path dataDirectory
    ) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        loadRuntimeLibraries();
        this.runtime = new HyperZoneLoginMain(proxy, logger, dataDirectory, this);
        HyperZoneApiProvider.INSTANCE.bind(this);
    }

    @Subscribe
    public void onEnable(ProxyInitializeEvent event) {
        this.runtime.onEnable(event);
    }

    @Override
    public ProxyServer getProxy() {
        return this.proxy;
    }

    public ComponentLogger getLogger() {
        return this.logger;
    }

    @Override
    public Path getDataDirectory() {
        return this.dataDirectory;
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
    public void registerModule(HyperSubModule module) {
        this.runtime.registerModule(module, this);
    }

    private void loadRuntimeLibraries() {
        try {
            List<HyperDependency> dependencies = readRuntimeDependencies();
            new HyperDependencyManager(
                this.dataDirectory.resolve("libs"),
                new VelocityHyperDependencyClassPathAppender(this.proxy, this),
                HyperDependencyRepository.DEFAULT_REPOSITORIES,
                new HyperDependencyProgressListener() {
                    @Override
                    public void onDownloadStart(icu.h2l.api.dependency.HyperDependency dependency, HyperDependencyRepository repository, Path targetPath) {
                        logger.info("正在下载运行库: {} <- {}", dependency.id(), repository.getBaseUrl());
                    }

                    @Override
                    public void onDownloadSuccess(icu.h2l.api.dependency.HyperDependency dependency, HyperDependencyRepository repository, Path targetPath) {
                        logger.info("运行库下载完成: {}", dependency.id());
                    }

                    @Override
                    public void onDownloadFailure(icu.h2l.api.dependency.HyperDependency dependency, HyperDependencyRepository repository, Exception exception) {
                        logger.warn("运行库下载失败: {} <- {} ({})", dependency.id(), repository.getBaseUrl(), exception.getMessage());
                    }
                }
            ).loadDependencies(dependencies);
            this.logger.info("核心运行库已完成动态加载");
        } catch (Exception e) {
            throw new IllegalStateException("无法加载 HyperZoneLogin 核心运行库", e);
        }
    }

    private List<HyperDependency> readRuntimeDependencies() throws IOException {
        List<HyperDependency> dependencies = HyperDependencyManifest.readFrom(getClass().getClassLoader());
        if (dependencies.isEmpty()) {
            throw new IllegalStateException("插件 jar 内未找到构建生成的运行库清单: " + HyperDependencyManifest.RESOURCE_PATH);
        }
        return dependencies;
    }
}


