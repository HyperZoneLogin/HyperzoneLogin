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
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Stable bridge plugin for vc-runtest.
 *
 * The bridge itself stays as a normal Velocity plugin instance, but HyperZoneLogin
 * runtime classes are side-loaded through a dedicated child-first classloader.
 * This keeps the plugin container identity valid for Velocity event registration
 * while allowing runtime classes to resolve dependency-plugin classes (for example
 * Floodgate API) through Velocity's plugin dependency classloader graph.
 */
public final class VcRuntestPlugin implements HyperZoneApi {
    private static final String HZL_MAIN_CLASS_NAME = "icu.h2l.login.HyperZoneLoginMain";

    private final ProxyServer velocityProxy;
    private final ComponentLogger logger;
    private final Path runtimeDataDirectory;

    private volatile Object runtime;
    private volatile Method runtimeOnEnable;
    private volatile Method runtimeGetDatabaseManager;
    private volatile Method runtimeGetHyperZonePlayers;
    private volatile Method runtimeGetChatCommandManager;
    private volatile Method runtimeGetServerAdapter;
    private volatile Method runtimeGetCredentialChannelRegistry;
    private volatile Method runtimeRegisterModule;

    @Inject
    public VcRuntestPlugin(
        ProxyServer proxy,
        ComponentLogger logger,
        @DataDirectory Path dataDirectory
    ) {
        this.velocityProxy = proxy;
        this.logger = logger;
        this.runtimeDataDirectory = dataDirectory.resolveSibling("hyperzonelogin");
    }

    @Subscribe
    public void onEnable(ProxyInitializeEvent event) throws Exception {
        Files.createDirectories(this.runtimeDataDirectory);
        HyperZoneApiProvider.INSTANCE.bind(this);

        ClassLoader pluginClassLoader = getClass().getClassLoader();
        List<ClassLoader> dependencyClassLoaders = resolveDependencyPluginClassLoaders();
        ClassLoader runtimeClassLoader = new ChildFirstRuntimeClassLoader(
            buildRuntimeClasspathUrls(),
            pluginClassLoader,
            dependencyClassLoaders
        );

        Class<?> runtimeClass = Class.forName(HZL_MAIN_CLASS_NAME, true, runtimeClassLoader);
        Constructor<?> constructor = runtimeClass.getConstructor(
            ProxyServer.class,
            ComponentLogger.class,
            Path.class,
            HyperZoneApi.class
        );
        Object runtimeInstance = constructor.newInstance(
            this.velocityProxy,
            this.logger,
            this.runtimeDataDirectory,
            this
        );

        this.runtime = runtimeInstance;
        this.runtimeOnEnable = runtimeClass.getMethod("onEnable", ProxyInitializeEvent.class);
        this.runtimeGetDatabaseManager = runtimeClass.getMethod("getDatabaseManager");
        this.runtimeGetHyperZonePlayers = runtimeClass.getMethod("getHyperZonePlayers");
        this.runtimeGetChatCommandManager = runtimeClass.getMethod("getChatCommandManager");
        this.runtimeGetServerAdapter = runtimeClass.getMethod("getServerAdapter");
        this.runtimeGetCredentialChannelRegistry = runtimeClass.getMethod("getCredentialChannelRegistry");
        this.runtimeRegisterModule = runtimeClass.getMethod("registerModule", HyperSubModule.class, HyperZoneApi.class);

        this.runtimeOnEnable.invoke(runtimeInstance, event);
        this.logger.info("vc-runtest bridge: HyperZoneLogin started from side-loaded runtime classpath");
    }

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
        return invokeRuntime(HyperZoneDatabaseManager.class, this.runtimeGetDatabaseManager);
    }

    @Override
    public HyperZonePlayerAccessor getHyperZonePlayers() {
        return invokeRuntime(HyperZonePlayerAccessor.class, this.runtimeGetHyperZonePlayers);
    }

    @Override
    public HyperChatCommandManager getChatCommandManager() {
        return invokeRuntime(HyperChatCommandManager.class, this.runtimeGetChatCommandManager);
    }

    @Override
    public HyperZoneVServerAdapter getServerAdapter() {
        return invokeRuntime(HyperZoneVServerAdapter.class, this.runtimeGetServerAdapter);
    }

    @Override
    public CredentialChannelRegistry getCredentialChannelRegistry() {
        return invokeRuntime(CredentialChannelRegistry.class, this.runtimeGetCredentialChannelRegistry);
    }

    @Override
    public void registerModule(HyperSubModule module) {
        Object runtimeInstance = requireRuntime();
        try {
            this.runtimeRegisterModule.invoke(runtimeInstance, module, this);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to register module through side-loaded HyperZoneLogin runtime", e);
        }
    }

    private <T> T invokeRuntime(Class<T> expectedType, Method method) {
        Object runtimeInstance = requireRuntime();
        try {
            Object result = method.invoke(runtimeInstance);
            return expectedType.cast(result);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to invoke runtime method " + method.getName(), e);
        }
    }

    private Object requireRuntime() {
        Object runtimeInstance = this.runtime;
        if (runtimeInstance == null) {
            throw new IllegalStateException("HyperZoneLogin runtime has not been initialized yet");
        }
        return runtimeInstance;
    }

    private static URL[] buildRuntimeClasspathUrls() {
        List<URL> urls = new ArrayList<>();
        String classPath = System.getProperty("java.class.path", "");
        for (String entry : classPath.split(File.pathSeparator)) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            Path path = Path.of(entry).toAbsolutePath().normalize();
            if (!Files.exists(path)) {
                continue;
            }
            try {
                urls.add(path.toUri().toURL());
            } catch (Exception e) {
                throw new IllegalStateException("Unable to add runtime classpath entry: " + path, e);
            }
        }
        return urls.toArray(URL[]::new);
    }

    private static final class ChildFirstRuntimeClassLoader extends URLClassLoader {
        private final List<ClassLoader> dependencyClassLoaders;

        ChildFirstRuntimeClassLoader(URL[] urls, ClassLoader parent, List<ClassLoader> dependencyClassLoaders) {
            super(urls, parent);
            this.dependencyClassLoaders = dependencyClassLoaders;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!shouldLoadChildFirst(name)) {
                try {
                    return super.loadClass(name, resolve);
                } catch (ClassNotFoundException ignored) {
                    Class<?> fromDependency = tryDependencyClassLoaders(name);
                    if (fromDependency != null) {
                        if (resolve) {
                            resolveClass(fromDependency);
                        }
                        return fromDependency;
                    }
                    throw ignored;
                }
            }

            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    try {
                        loaded = findClass(name);
                    } catch (ClassNotFoundException ignored) {
                        Class<?> fromDependency = tryDependencyClassLoaders(name);
                        loaded = fromDependency != null ? fromDependency : super.loadClass(name, false);
                    }
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        private static boolean shouldLoadChildFirst(String className) {
            return className.startsWith("icu.h2l.login.")
                && !className.startsWith("icu.h2l.login.vcruntest.");
        }

        private Class<?> tryDependencyClassLoaders(String className) {
            for (ClassLoader classLoader : this.dependencyClassLoaders) {
                try {
                    return Class.forName(className, false, classLoader);
                } catch (ClassNotFoundException ignored) {
                    // Try next dependency loader.
                }
            }
            return null;
        }
    }

    private List<ClassLoader> resolveDependencyPluginClassLoaders() {
        List<ClassLoader> classLoaders = new ArrayList<>();
        addDependencyPluginClassLoader(classLoaders, "floodgate");
        return classLoaders;
    }

    private void addDependencyPluginClassLoader(List<ClassLoader> out, String pluginId) {
        Optional<?> pluginContainer = this.velocityProxy.getPluginManager().getPlugin(pluginId);
        if (pluginContainer.isEmpty()) {
            return;
        }
        try {
            Object container = pluginContainer.get();
            Method getInstanceMethod = container.getClass().getMethod("getInstance");
            Object instanceOptional = getInstanceMethod.invoke(container);
            if (!(instanceOptional instanceof Optional<?> optionalInstance) || optionalInstance.isEmpty()) {
                return;
            }
            Object pluginInstance = optionalInstance.get();
            ClassLoader classLoader = pluginInstance.getClass().getClassLoader();
            if (classLoader != null && !out.contains(classLoader)) {
                out.add(classLoader);
            }
        } catch (ReflectiveOperationException ignored) {
            // Leave unresolved; runtime will continue and may still resolve from parent/urls.
        }
    }
}
