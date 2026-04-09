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
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Reads build-generated runtime dependency metadata from plugin jars.
 */
public final class HyperDependencyManifest {
    public static final String RESOURCE_PATH = "META-INF/hzl/runtime-dependencies.properties";

    private HyperDependencyManifest() {
    }

    public static List<HyperDependency> readFrom(ClassLoader classLoader) throws IOException {
        try (InputStream in = classLoader.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                return List.of();
            }

            Properties properties = new Properties();
            properties.load(in);
            int count = Integer.parseInt(properties.getProperty("count", "0"));
            List<HyperDependency> dependencies = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                dependencies.add(
                    new HyperDependency(
                        required(properties, index, "group"),
                        required(properties, index, "name"),
                        required(properties, index, "version"),
                        required(properties, index, "sha256")
                    )
                );
            }
            return List.copyOf(dependencies);
        }
    }

    private static String required(Properties properties, int index, String key) {
        String property = properties.getProperty("dependency." + index + "." + key);
        if (property == null || property.isBlank()) {
            throw new IllegalStateException("Missing runtime dependency metadata field: dependency." + index + "." + key);
        }
        return property;
    }
}

