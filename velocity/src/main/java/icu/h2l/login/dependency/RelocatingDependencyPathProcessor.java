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

package icu.h2l.login.dependency;

import icu.h2l.api.dependency.HyperDependency;
import icu.h2l.api.dependency.HyperDependencyDownloadException;
import icu.h2l.api.dependency.HyperDependencyPathProcessor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import me.lucko.jarrelocator.JarRelocator;

public final class RelocatingDependencyPathProcessor implements HyperDependencyPathProcessor {
    private final List<Rule> rules;

    public RelocatingDependencyPathProcessor(Rule... rules) {
        this.rules = List.copyOf(Arrays.asList(rules));
    }

    @Override
    public Path process(HyperDependency dependency, Path downloadedFile) throws HyperDependencyDownloadException {
        for (Rule rule : this.rules) {
            if (rule.matches(dependency)) {
                return relocate(rule, dependency, downloadedFile);
            }
        }
        return downloadedFile;
    }

    private Path relocate(Rule rule, HyperDependency dependency, Path downloadedFile) throws HyperDependencyDownloadException {
        Path relocatedDirectory = downloadedFile.getParent().resolve("relocated").resolve(rule.cacheKey());
        Path relocatedFile = relocatedDirectory.resolve(relocatedFileName(downloadedFile));
        Path tempFile = relocatedDirectory.resolve(relocatedFile.getFileName() + ".tmp");

        try {
            Files.createDirectories(relocatedDirectory);
            if (Files.exists(relocatedFile)
                && Files.getLastModifiedTime(relocatedFile).compareTo(Files.getLastModifiedTime(downloadedFile)) >= 0) {
                return relocatedFile;
            }

            Files.deleteIfExists(tempFile);
            Files.deleteIfExists(relocatedFile);
            new JarRelocator(downloadedFile.toFile(), tempFile.toFile(), rule.relocations()).run();
            Files.move(tempFile, relocatedFile, StandardCopyOption.REPLACE_EXISTING);
            return relocatedFile;
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
                // ignored
            }
            throw new HyperDependencyDownloadException("Unable to relocate dependency " + dependency, e);
        }
    }

    private static String relocatedFileName(Path downloadedFile) {
        String fileName = downloadedFile.getFileName().toString();
        if (fileName.endsWith(".jar")) {
            return fileName.substring(0, fileName.length() - 4) + "-relocated.jar";
        }
        return fileName + "-relocated";
    }

    public static final class Rule {
        private final String groupId;
        private final String artifactId;
        private final String cacheKey;
        private final Map<String, String> relocations;

        private Rule(String groupId, String artifactId, String cacheKey, Map<String, String> relocations) {
            this.groupId = Objects.requireNonNull(groupId, "groupId");
            this.artifactId = artifactId;
            this.cacheKey = sanitizePathSegment(Objects.requireNonNull(cacheKey, "cacheKey"));
            this.relocations = Map.copyOf(Objects.requireNonNull(relocations, "relocations"));
            if (this.relocations.isEmpty()) {
                throw new IllegalArgumentException("relocations cannot be empty");
            }
        }

        public static Rule forGroup(String groupId, Map<String, String> relocations) {
            return new Rule(groupId, null, "group-" + groupId, relocations);
        }

        public static Rule forDependency(String groupId, String artifactId, Map<String, String> relocations) {
            return new Rule(groupId, Objects.requireNonNull(artifactId, "artifactId"), "dependency-" + groupId + "-" + artifactId, relocations);
        }

        private boolean matches(HyperDependency dependency) {
            if (!this.groupId.equals(dependency.groupId())) {
                return false;
            }
            return this.artifactId == null || this.artifactId.equals(dependency.artifactId());
        }

        private String cacheKey() {
            return this.cacheKey;
        }

        private Map<String, String> relocations() {
            return this.relocations;
        }

        private static String sanitizePathSegment(String value) {
            return value.replaceAll("[^A-Za-z0-9._-]", "_");
        }
    }
}

