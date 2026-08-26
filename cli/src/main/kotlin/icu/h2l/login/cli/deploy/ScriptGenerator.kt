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
 * Generates startup scripts (.bat for Windows and .sh for Linux/Mac) for each server.
 * Scripts will automatically download required libraries from the PaperMC API if needed.
 */
class ScriptGenerator(
    private val baseDir: File,
) {

    fun generateAllScripts() {
        generateVelocityScripts()
        generateLobbyScripts()
        generateGameScripts()
        generateWindowsLaunchAllScript()
    }

    private fun generateVelocityScripts() {
        val serverDir = baseDir.resolve("velocity")
        serverDir.mkdirs()

        val batContent = """
            @echo off
            setlocal enabledelayedexpansion

            cd /d "%~dp0"

            set "JAR_FILE="
            for %%f in (velocity-*.jar) do (
                set "JAR_FILE=%%f"
                goto found
            )
            :found

            if "!JAR_FILE!"=="" (
                echo ERROR: No Velocity JAR found in this directory
                echo Please ensure velocity-*.jar exists
                pause
                exit /b 1
            )

            echo Starting Velocity proxy from !JAR_FILE!...
            java -jar "!JAR_FILE!"

            pause
        """.trimIndent()

        val shContent = """
            #!/bin/bash

            cd "${'$'}(dirname "${'$'}0")"

            JAR_FILE=$(ls -t velocity-*.jar 2>/dev/null | head -n 1)

            if [ -z "${'$'}JAR_FILE" ]; then
                echo "ERROR: No Velocity JAR found in this directory"
                echo "Please ensure velocity-*.jar exists"
                exit 1
            fi

            echo "Starting Velocity proxy from ${'$'}JAR_FILE..."

            java -jar "${'$'}JAR_FILE"
        """.trimIndent()

        serverDir.resolve("start.bat").writeText(batContent + "\n")
        val shFile = serverDir.resolve("start.sh")
        shFile.writeText(shContent + "\n")
        // Make shell script executable on Unix-like systems
        shFile.setExecutable(true)

        println("  [write]  ${serverDir.resolve("start.bat").name}")
        println("  [write]  ${serverDir.resolve("start.sh").name}")
    }

    private fun generateLobbyScripts() {
        val serverDir = baseDir.resolve("lobby")
        serverDir.mkdirs()

        val batContent = """
            @echo off
            setlocal enabledelayedexpansion

            cd /d "%~dp0"

            set "JAR_FILE="
            for %%f in (paper-*.jar) do (
                set "JAR_FILE=%%f"
                goto found
            )
            :found

            if "!JAR_FILE!"=="" (
                echo ERROR: No Paper JAR found in this directory
                echo Please ensure paper-*.jar exists
                pause
                exit /b 1
            )

            echo Starting Lobby server from !JAR_FILE!...
            java -jar "!JAR_FILE!" nogui

            pause
        """.trimIndent()

        val shContent = """
            #!/bin/bash

            cd "${'$'}(dirname "${'$'}0")"

            JAR_FILE=$(ls -t paper-*.jar 2>/dev/null | head -n 1)

            if [ -z "${'$'}JAR_FILE" ]; then
                echo "ERROR: No Paper JAR found in this directory"
                echo "Please ensure paper-*.jar exists"
                exit 1
            fi

            echo "Starting Lobby server from ${'$'}JAR_FILE..."

            java -jar "${'$'}JAR_FILE" nogui
        """.trimIndent()

        serverDir.resolve("start.bat").writeText(batContent + "\n")
        val shFile = serverDir.resolve("start.sh")
        shFile.writeText(shContent + "\n")
        shFile.setExecutable(true)

        println("  [write]  ${serverDir.resolve("start.bat").name}")
        println("  [write]  ${serverDir.resolve("start.sh").name}")
    }

    private fun generateGameScripts() {
        val serverDir = baseDir.resolve("game")
        serverDir.mkdirs()

        val batContent = """
            @echo off
            setlocal enabledelayedexpansion

            cd /d "%~dp0"

            set "JAR_FILE="
            for %%f in (paper-*.jar) do (
                set "JAR_FILE=%%f"
                goto found
            )
            :found

            if "!JAR_FILE!"=="" (
                echo ERROR: No Paper JAR found in this directory
                echo Please ensure paper-*.jar exists
                pause
                exit /b 1
            )

            echo Starting Game server from !JAR_FILE!...
            java -jar "!JAR_FILE!" nogui

            pause
        """.trimIndent()

        val shContent = """
            #!/bin/bash

            cd "${'$'}(dirname "${'$'}0")"

            JAR_FILE=$(ls -t paper-*.jar 2>/dev/null | head -n 1)

            if [ -z "${'$'}JAR_FILE" ]; then
                echo "ERROR: No Paper JAR found in this directory"
                echo "Please ensure paper-*.jar exists"
                exit 1
            fi

            echo "Starting Game server from ${'$'}JAR_FILE..."

            java -jar "${'$'}JAR_FILE" nogui
        """.trimIndent()

        serverDir.resolve("start.bat").writeText(batContent + "\n")
        val shFile = serverDir.resolve("start.sh")
        shFile.writeText(shContent + "\n")
        shFile.setExecutable(true)

        println("  [write]  ${serverDir.resolve("start.bat").name}")
        println("  [write]  ${serverDir.resolve("start.sh").name}")
    }

    private fun generateWindowsLaunchAllScript() {
        val script = """
            @echo off
            setlocal

            set "BASE_DIR=%~dp0"
            set "BASE_DIR=%BASE_DIR:~0,-1%"

            where wt >nul 2>nul
            if errorlevel 1 (
                echo Windows Terminal (wt.exe) not found. Falling back to start commands.
                start "HZL Lobby" cmd /k "cd /d ""%BASE_DIR%\lobby"" && start.bat"
                start "HZL Game" cmd /k "cd /d ""%BASE_DIR%\game"" && start.bat"
                start "HZL Velocity" cmd /k "cd /d ""%BASE_DIR%\velocity"" && start.bat"
                exit /b 0
            )

            wt cmd /k "cd /d ""%BASE_DIR%\lobby"" && start.bat" ^
             ; new-tab cmd /k "cd /d ""%BASE_DIR%\game"" && start.bat" ^
             ; new-tab cmd /k "cd /d ""%BASE_DIR%\velocity"" && start.bat"
        """.trimIndent() + "\n"

        val launchAll = baseDir.resolve("start-all.bat")
        launchAll.writeText(script)
        println("  [write]  ${launchAll.name}")
    }
}

