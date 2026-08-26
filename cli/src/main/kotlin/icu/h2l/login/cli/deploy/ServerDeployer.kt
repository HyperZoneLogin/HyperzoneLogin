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

package icu.h2l.login.cli.deploy

import java.io.File

/**
 * Base helper for all server deployers. Provides file-writing utilities that
 * respect the [overwrite] flag: existing files are never clobbered unless the
 * flag is set.
 */
abstract class ServerDeployer(
    protected val overwrite: Boolean,
) {

    abstract fun deploy()

    /**
     * Write [content] to [file], creating parent directories as needed.
     * Returns `true` when the file was actually written, `false` when it was
     * skipped because it already existed and [overwrite] is `false`.
     */
    protected fun writeFile(file: File, content: String): Boolean {
        file.parentFile?.mkdirs()
        if (file.exists() && !overwrite) {
            println("  [skip]   ${file.path}  (already exists, use --overwrite to replace)")
            return false
        }
        file.writeText(content)
        println("  [write]  ${file.path}")
        return true
    }

    /**
     * Copy [source] to [dest], creating parent directories as needed.
     * Returns `true` when the copy occurred.
     */
    protected fun copyFile(source: File, dest: File): Boolean {
        dest.parentFile?.mkdirs()
        if (dest.exists() && !overwrite) {
            println("  [skip]   ${dest.path}  (already exists, use --overwrite to replace)")
            return false
        }
        source.copyTo(dest, overwrite = true)
        println("  [copy]   ${dest.path}")
        return true
    }
}

