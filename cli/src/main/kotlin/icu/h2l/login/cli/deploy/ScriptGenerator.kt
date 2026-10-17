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
 * Represents a server that needs startup scripts generated.
 */
data class ServerScript(
    /** Relative directory name (e.g., "play", "velocity") */
    val dirName: String,
    /** Human-readable label for window titles and messages (e.g., "HZL Game") */
    val label: String,
    /** JAR file pattern to search for (e.g., "paper-*.jar", "velocity-*.jar") */
    val jarPattern: String,
    /** Whether this is a Paper server (uses "nogui" flag) */
    val isPaperServer: Boolean,
)

/**
 * Generates startup scripts (.bat for Windows and .sh for Linux/Mac) for each server.
 * Scripts will automatically download required libraries from the PaperMC API if needed.
 */
class ScriptGenerator(
    private val baseDir: File,
) {

    fun generateAllScripts(servers: List<ServerScript>, velocityConfig: ServerScript) {
        generateVelocityScripts(velocityConfig)
        servers.forEach { generatePaperScripts(it) }
        generateWindowsLaunchAllScript(servers, velocityConfig)
        generateWindowsStopAllScript(servers, velocityConfig)
    }

    private fun generateVelocityScripts(config: ServerScript) {
        val serverDir = baseDir.resolve(config.dirName)
        serverDir.mkdirs()

        val batContent = """
            @echo off
            setlocal enabledelayedexpansion
            title ${config.label}

            cd /d "%~dp0"

            :loop
            set "JAR_FILE="
            for %%f in (${config.jarPattern}) do (
                set "JAR_FILE=%%f"
                goto found
            )
            :found

            if "!JAR_FILE!"=="" (
                echo ERROR: No Velocity JAR found in this directory
                echo Please ensure ${config.jarPattern} exists
                pause
                exit /b 1
            )

            echo Starting ${config.label} proxy from !JAR_FILE!...
            java -jar "!JAR_FILE!"

            echo.
            echo ${config.label} proxy stopped. Press any key to restart, or close this window to exit.
            pause >nul
            goto loop
        """.trimIndent()

        val shContent = """
            #!/bin/bash
            printf '\033]0;${config.label}\007'

            cd "${'$'}(dirname "${'$'}0")"

            while true; do
                JAR_FILE=$(ls -t ${config.jarPattern} 2>/dev/null | head -n 1)

                if [ -z "${'$'}JAR_FILE" ]; then
                    echo "ERROR: No Velocity JAR found in this directory"
                    echo "Please ensure ${config.jarPattern} exists"
                    exit 1
                fi

                echo "Starting ${config.label} proxy from ${'$'}JAR_FILE..."
                java -jar "${'$'}JAR_FILE"

                echo ""
                echo "${config.label} proxy stopped. Press Enter to restart (Ctrl+C to exit)."
                read -r
            done
        """.trimIndent()

        serverDir.resolve("start.bat").writeText(batContent + "\n")
        val shFile = serverDir.resolve("start.sh")
        shFile.writeText(shContent + "\n")
        // Make shell script executable on Unix-like systems
        shFile.setExecutable(true)

        println("  [write]  ${serverDir.resolve("start.bat").name}")
        println("  [write]  ${serverDir.resolve("start.sh").name}")
    }

    private fun generatePaperScripts(config: ServerScript) {
        val serverDir = baseDir.resolve(config.dirName)
        serverDir.mkdirs()

        val nogui = if (config.isPaperServer) " nogui" else ""

        val batContent = """
            @echo off
            setlocal enabledelayedexpansion
            title ${config.label}

            cd /d "%~dp0"

            :loop
            set "JAR_FILE="
            for %%f in (${config.jarPattern}) do (
                set "JAR_FILE=%%f"
                goto found
            )
            :found

            if "!JAR_FILE!"=="" (
                echo ERROR: No Paper JAR found in this directory
                echo Please ensure ${config.jarPattern} exists
                pause
                exit /b 1
            )

            echo Starting ${config.label} server from !JAR_FILE!...
            java -jar "!JAR_FILE!"$nogui

            echo.
            echo ${config.label} server stopped. Press any key to restart, or close this window to exit.
            pause >nul
            goto loop
        """.trimIndent()

        val shContent = """
            #!/bin/bash
            printf '\033]0;${config.label}\007'

            cd "${'$'}(dirname "${'$'}0")"

            while true; do
                JAR_FILE=$(ls -t ${config.jarPattern} 2>/dev/null | head -n 1)

                if [ -z "${'$'}JAR_FILE" ]; then
                    echo "ERROR: No Paper JAR found in this directory"
                    echo "Please ensure ${config.jarPattern} exists"
                    exit 1
                fi

                echo "Starting ${config.label} server from ${'$'}JAR_FILE..."
                java -jar "${'$'}JAR_FILE"$nogui

                echo ""
                echo "${config.label} server stopped. Press Enter to restart (Ctrl+C to exit)."
                read -r
            done
        """.trimIndent()

        serverDir.resolve("start.bat").writeText(batContent + "\n")
        val shFile = serverDir.resolve("start.sh")
        shFile.writeText(shContent + "\n")
        shFile.setExecutable(true)

        println("  [write]  ${serverDir.resolve("start.bat").name}")
        println("  [write]  ${serverDir.resolve("start.sh").name}")
    }

    private fun generateWindowsLaunchAllScript(servers: List<ServerScript>, velocityConfig: ServerScript) {
        val allConfigs = servers + listOf(velocityConfig)

        // For wt (Windows Terminal), build command with proper quoting
        val wtCommands = allConfigs.mapIndexed { index, config ->
            if (index == 0) {
                """wt -w 0 new-tab --title "${config.label}" -d "!BASE_DIR!\${config.dirName}" cmd /k call start.bat"""
            } else {
                """; new-tab --title "${config.label}" -d "!BASE_DIR!\${config.dirName}" cmd /k call start.bat"""
            }
        }.joinToString(" ")

        val serverStarts = allConfigs.joinToString("\n                ") { config ->
            """start "" "cmd" /k "title ${config.label} & cd /d ""%BASE_DIR%\${config.dirName}"" & call start.bat""""
        }

        val script = """
            @echo off
            setlocal enabledelayedexpansion
            title HZL Control

            set "BASE_DIR=%~dp0"
            set "BASE_DIR=!BASE_DIR:~0,-1!"

            where wt >nul 2>nul
            if errorlevel 1 (
                echo Windows Terminal ^(wt.exe^) not found. Falling back to start commands.
                $serverStarts
                exit /b 0
            )

            $wtCommands
        """.trimIndent() + "\n"

        val launchAll = baseDir.resolve("start-all.bat")
        launchAll.writeText(script)
        println("  [write]  ${launchAll.name}")
    }

    private fun generateWindowsStopAllScript(servers: List<ServerScript>, velocityConfig: ServerScript) {
        // Paper/Minecraft servers shut down gracefully with the "stop" console command;
        // Velocity shuts down with "end". We send each command to the matching server
        // terminal window (by title) instead of force-killing the JVM.
        val targets = buildList {
            servers.forEach { config -> add(config.label to "stop") }
            add(velocityConfig.label to "end")
        }

        val sendKeys = targets.joinToString(" ") { (title, command) ->
            "if (${'$'}ws.AppActivate('$title')) { Start-Sleep -Milliseconds 600; ${'$'}ws.SendKeys('$command{ENTER}') };"
        }

        val script = """
            @echo off
            setlocal
            title HZL Control

            echo Sending graceful stop command to all server terminals...

            powershell -NoProfile -ExecutionPolicy Bypass -Command "${'$'}ws = New-Object -ComObject WScript.Shell; $sendKeys Start-Sleep -Milliseconds 500; ${'$'}ws.AppActivate('HZL Control') | Out-Null;"

            echo.
            echo Done. Each server window will show a restart prompt after it shuts down.
            pause
        """.trimIndent() + "\n"

        val stopAll = baseDir.resolve("stop-all.bat")
        stopAll.writeText(script)
        println("  [write]  ${stopAll.name}")
    }
}

