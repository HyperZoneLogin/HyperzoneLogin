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

package icu.h2l.api.dependency;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Adapted from LuckPerms' dependency manager implementation.
 */
public final class HyperDependencyManager {
    private final Path cacheDirectory;
    private final HyperDependencyClassPathAppender classPathAppender;
    private final Collection<HyperDependencyRepository> repositories;
    private final HyperDependencyProgressListener progressListener;
    private final Map<HyperDependency, Path> loaded = new LinkedHashMap<>();

    public HyperDependencyManager(Path cacheDirectory, HyperDependencyClassPathAppender classPathAppender) {
        this(cacheDirectory, classPathAppender, HyperDependencyRepository.DEFAULT_REPOSITORIES, HyperDependencyProgressListener.NONE);
    }

    public HyperDependencyManager(
        Path cacheDirectory,
        HyperDependencyClassPathAppender classPathAppender,
        Collection<HyperDependencyRepository> repositories
    ) {
        this(cacheDirectory, classPathAppender, repositories, HyperDependencyProgressListener.NONE);
    }

    public HyperDependencyManager(
        Path cacheDirectory,
        HyperDependencyClassPathAppender classPathAppender,
        Collection<HyperDependencyRepository> repositories,
        HyperDependencyProgressListener progressListener
    ) {
        this.cacheDirectory = Objects.requireNonNull(cacheDirectory, "cacheDirectory");
        this.classPathAppender = Objects.requireNonNull(classPathAppender, "classPathAppender");
        this.repositories = Objects.requireNonNull(repositories, "repositories");
        this.progressListener = Objects.requireNonNull(progressListener, "progressListener");
    }

    public synchronized void loadDependencies(Collection<HyperDependency> dependencies) throws HyperDependencyDownloadException {
        setupCacheDirectory();
        for (HyperDependency dependency : dependencies) {
            loadDependency(dependency);
        }
    }

    private void loadDependency(HyperDependency dependency) throws HyperDependencyDownloadException {
        if (this.loaded.containsKey(dependency)) {
            return;
        }

        Path file = downloadDependency(dependency);
        this.classPathAppender.addJarToClasspath(file);
        this.loaded.put(dependency, file);
        this.progressListener.onDependencyLoaded(dependency, file);
    }

    private Path downloadDependency(HyperDependency dependency) throws HyperDependencyDownloadException {
        Path file = this.cacheDirectory.resolve(dependency.cacheFileName());

        if (Files.exists(file) && checksumMatches(file, dependency)) {
            this.progressListener.onUsingCache(dependency, file);
            return file;
        }

        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new HyperDependencyDownloadException("Unable to replace cached dependency " + dependency, e);
        }

        HyperDependencyDownloadException lastError = null;
        for (HyperDependencyRepository repository : this.repositories) {
            try {
                this.progressListener.onDownloadStart(dependency, repository, file);
                repository.download(dependency, file);
                this.progressListener.onDownloadSuccess(dependency, repository, file);
                return file;
            } catch (HyperDependencyDownloadException e) {
                this.progressListener.onDownloadFailure(dependency, repository, e);
                lastError = e;
            }
        }

        throw Objects.requireNonNullElseGet(lastError, () -> new HyperDependencyDownloadException("No repository was available for dependency " + dependency));
    }

    private boolean checksumMatches(Path file, HyperDependency dependency) throws HyperDependencyDownloadException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] hash = HyperDependency.createDigest().digest(in.readAllBytes());
            return dependency.checksumMatches(hash);
        } catch (IOException e) {
            throw new HyperDependencyDownloadException("Unable to verify cached dependency " + dependency, e);
        }
    }

    private void setupCacheDirectory() throws HyperDependencyDownloadException {
        try {
            Files.createDirectories(this.cacheDirectory);
        } catch (IOException e) {
            throw new HyperDependencyDownloadException("Unable to create dependency cache directory " + this.cacheDirectory, e);
        }
    }
}

