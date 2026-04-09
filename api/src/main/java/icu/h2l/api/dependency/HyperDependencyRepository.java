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
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Adapted from LuckPerms' dependency repository logic.
 */
public enum HyperDependencyRepository {
    ALIYUN_CENTRAL("https://maven.aliyun.com/repository/central/"),
    TENCENT_MAVEN_PUBLIC("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/"),
    MAVEN_CENTRAL("https://repo1.maven.org/maven2/");

    public static final List<HyperDependencyRepository> DEFAULT_REPOSITORIES = List.of(
        ALIYUN_CENTRAL,
        TENCENT_MAVEN_PUBLIC,
        MAVEN_CENTRAL
    );

    private final String baseUrl;

    HyperDependencyRepository(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getBaseUrl() {
        return this.baseUrl;
    }

    private InputStream openStream(HyperDependency dependency) throws IOException {
        URL dependencyUrl = URI.create(this.baseUrl + dependency.getMavenRepoPath()).toURL();
        URLConnection connection = dependencyUrl.openConnection();
        connection.setRequestProperty("User-Agent", "hyperzonelogin");
        connection.setConnectTimeout((int) TimeUnit.SECONDS.toMillis(5));
        connection.setReadTimeout((int) TimeUnit.SECONDS.toMillis(15));
        return connection.getInputStream();
    }

    public byte[] downloadRaw(HyperDependency dependency) throws HyperDependencyDownloadException {
        try (InputStream in = openStream(dependency)) {
            if (in == null) {
                throw new HyperDependencyDownloadException("Repository " + this.name() + " returned null stream for dependency " + dependency);
            }
            byte[] bytes = in.readAllBytes();
            if (bytes.length == 0) {
                throw new HyperDependencyDownloadException("Repository " + this.name() + " returned an empty artifact for dependency " + dependency);
            }
            return bytes;
        } catch (IOException e) {
            throw new HyperDependencyDownloadException("Unable to download dependency " + dependency + " from " + this.baseUrl, e);
        }
    }

    public byte[] download(HyperDependency dependency) throws HyperDependencyDownloadException {
        byte[] bytes = downloadRaw(dependency);
        byte[] hash = HyperDependency.createDigest().digest(bytes);
        if (!dependency.checksumMatches(hash)) {
            throw new HyperDependencyDownloadException(
                "Downloaded file had an invalid hash. Expected: "
                    + Base64.getEncoder().encodeToString(dependency.getChecksum())
                    + " Actual: "
                    + Base64.getEncoder().encodeToString(hash)
            );
        }
        return bytes;
    }

    public void download(HyperDependency dependency, Path file) throws HyperDependencyDownloadException {
        Path temporaryFile = file.resolveSibling(file.getFileName() + ".part");
        try {
            Files.createDirectories(file.getParent());
            Files.write(temporaryFile, download(dependency));
            try {
                Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temporaryFile);
            } catch (IOException ignored) {
                // ignore cleanup failure
            }
            throw new HyperDependencyDownloadException("Unable to write dependency " + dependency + " to " + file, e);
        }
    }
}


