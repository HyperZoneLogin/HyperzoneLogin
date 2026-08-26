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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * CLI entry point. Handles dependency bootstrapping and command execution.
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        try {
            ClassLoader runtimeClassLoader = DependencyBootstrap.createRuntimeClassLoader();
            Thread thread = Thread.currentThread();
            ClassLoader previousContextClassLoader = thread.getContextClassLoader();
            thread.setContextClassLoader(runtimeClassLoader);
            try {
                Class<?> entrypointClass = Class.forName(DependencyBootstrap.cliEntrypointClassName(), true, runtimeClassLoader);
                Method runMethod = entrypointClass.getMethod("run", String[].class);
                Object result = runMethod.invoke(null, (Object) args);
                int exitCode = result instanceof Number number ? number.intValue() : 0;
                System.exit(exitCode);
            } finally {
                thread.setContextClassLoader(previousContextClassLoader);
            }
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            System.err.println("Failed to start CLI: " + cause.getMessage());
            cause.printStackTrace(System.err);
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Failed to start CLI: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }
}

