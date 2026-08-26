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

package icu.h2l.login.cli;

import icu.h2l.api.dependency.HyperDependencyClassPathAppender;
import icu.h2l.api.dependency.HyperDependencyDownloadException;
import icu.h2l.api.dependency.HyperDependencyManager;
import icu.h2l.api.dependency.HyperDependencyManifest;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Bootstraps runtime dependencies before running the CLI.
 */
public final class DependencyBootstrap {
    private static final String CLI_ENTRYPOINT_CLASS_NAME = "icu.h2l.login.cli.CliEntrypoint";

    private DependencyBootstrap() {
    }

    public static String cliEntrypointClassName() {
        return CLI_ENTRYPOINT_CLASS_NAME;
    }

    public static ClassLoader createRuntimeClassLoader() throws IOException, HyperDependencyDownloadException {
        var appender = new RuntimeClassLoaderBuilder();
        var dependencies = HyperDependencyManifest.readFrom(Thread.currentThread().getContextClassLoader());
        if (!dependencies.isEmpty()) {
            var cacheDir = resolveCacheDirectory();
            System.out.println("Using CLI runtime library cache: " + cacheDir.toAbsolutePath());
            var dependencyManager = new HyperDependencyManager(cacheDir, appender);
            dependencyManager.loadDependencies(dependencies);
        }
        return appender.build();
    }

    private static Path resolveCacheDirectory() {
        String override = System.getProperty("h2l.cliLibDir");
        if (override != null && !override.isBlank()) {
            return Paths.get(override).toAbsolutePath().normalize();
        }
        return Paths.get("").toAbsolutePath().resolve(".h2l").resolve("libs").normalize();
    }

    /**
     * Builds an isolated runtime class loader containing the current application classpath
     * plus downloaded runtime dependencies.
     */
    private static final class RuntimeClassLoaderBuilder implements HyperDependencyClassPathAppender {
        private final Set<URL> urls = new LinkedHashSet<>();

        RuntimeClassLoaderBuilder() {
            addCurrentClasspathEntries();
        }

        private void addCurrentClasspathEntries() {
            String classPath = System.getProperty("java.class.path", "");
            for (String entry : classPath.split(File.pathSeparator)) {
                if (entry == null || entry.isBlank()) {
                    continue;
                }
                Path path = Path.of(entry);
                if (java.nio.file.Files.exists(path)) {
                    add(path);
                }
            }

            if (!this.urls.isEmpty()) {
                return;
            }

            CodeSource codeSource = DependencyBootstrap.class.getProtectionDomain().getCodeSource();
            if (codeSource != null && codeSource.getLocation() != null) {
                this.urls.add(codeSource.getLocation());
            }
        }

        @Override
        public void addJarToClasspath(Path file) {
            add(file);
        }

        private void add(Path file) {
            try {
                this.urls.add(file.toUri().toURL());
            } catch (Exception e) {
                throw new IllegalStateException("Unable to add classpath entry " + file, e);
            }
        }

        private ClassLoader build() {
            return new URLClassLoader(this.urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
        }
    }
}


