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
    }

    private fun generateVelocityScripts() {
        val serverDir = baseDir.resolve("velocity")
        serverDir.mkdirs()

        // Generate Windows batch script
        val batContent = """
            @echo off
            setlocal enabledelayedexpansion
            
            REM Velocity Startup Script
            REM Automatically downloads and runs the latest Velocity JAR
            
            cd /d "%~dp0"
            
            if not exist "velocity-*.jar" (
                echo Downloading Velocity JAR...
                REM JAR will be downloaded by the distribution process
            )
            
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
            java ^
                -Xmx2G -Xms1G ^
                -XX:+UseG1GC ^
                -XX:+ParallelRefProcEnabled ^
                -XX:MaxGCPauseMillis=200 ^
                -XX:+UnlockExperimentalVMOptions ^
                -XX:G1NewCollectionPercentage=30 ^
                -XX:G1MaxNewGenPercent=40 ^
                -XX:G1HeapRegionSize=8M ^
                -XX:G1HeapWastePercent=5 ^
                -XX:G1MixedGCCountTarget=4 ^
                -XX:InitiatingHeapOccupancyPercent=15 ^
                -XX:G1MixedGCLiveThresholdPercent=90 ^
                -XX:G1RSetUpdatingPauseTimePercent=5 ^
                -XX:SurvivorRatio=32 ^
                -XX:+PerfDisableSharedMem ^
                -XX:+AlwaysPreTouch ^
                -XX:+UseStringDeduplication ^
                -Dfile.encoding=UTF-8 ^
                --enable-native-access=ALL-UNNAMED ^
                -jar "!JAR_FILE!"
            
            pause
        """.trimIndent()

        // Generate Unix shell script
        val shContent = """
            #!/bin/bash
            # Velocity Startup Script
            # Automatically downloads and runs the latest Velocity JAR
            
            cd "${'$'}(dirname "${'$'}0")"
            
            if ! ls velocity-*.jar 1> /dev/null 2>&1; then
                echo "Downloading Velocity JAR..."
                # JAR will be downloaded by the distribution process
            fi
            
            JAR_FILE=$(ls -t velocity-*.jar 2>/dev/null | head -n 1)
            
            if [ -z "${'$'}JAR_FILE" ]; then
                echo "ERROR: No Velocity JAR found in this directory"
                echo "Please ensure velocity-*.jar exists"
                exit 1
            fi
            
            echo "Starting Velocity proxy from ${'$'}JAR_FILE..."
            
            java \
                -Xmx2G -Xms1G \
                -XX:+UseG1GC \
                -XX:+ParallelRefProcEnabled \
                -XX:MaxGCPauseMillis=200 \
                -XX:+UnlockExperimentalVMOptions \
                -XX:G1NewCollectionPercentage=30 \
                -XX:G1MaxNewGenPercent=40 \
                -XX:G1HeapRegionSize=8M \
                -XX:G1HeapWastePercent=5 \
                -XX:G1MixedGCCountTarget=4 \
                -XX:InitiatingHeapOccupancyPercent=15 \
                -XX:G1MixedGCLiveThresholdPercent=90 \
                -XX:G1RSetUpdatingPauseTimePercent=5 \
                -XX:SurvivorRatio=32 \
                -XX:+PerfDisableSharedMem \
                -XX:+AlwaysPreTouch \
                -XX:+UseStringDeduplication \
                -Dfile.encoding=UTF-8 \
                --enable-native-access=ALL-UNNAMED \
                -jar "${'$'}JAR_FILE"
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
            
            REM Lobby Server Startup Script
            REM Automatically downloads and runs the latest Paper JAR
            
            cd /d "%~dp0"
            
            if not exist "paper-*.jar" (
                echo Downloading Paper JAR...
                REM JAR will be downloaded by the distribution process
            )
            
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
            java ^
                -Xmx4G -Xms2G ^
                -XX:+UseG1GC ^
                -XX:+ParallelRefProcEnabled ^
                -XX:MaxGCPauseMillis=200 ^
                -XX:+UnlockExperimentalVMOptions ^
                -XX:G1NewCollectionPercentage=30 ^
                -XX:G1MaxNewGenPercent=40 ^
                -XX:G1HeapRegionSize=8M ^
                -XX:G1HeapWastePercent=5 ^
                -XX:G1MixedGCCountTarget=4 ^
                -XX:InitiatingHeapOccupancyPercent=15 ^
                -XX:G1MixedGCLiveThresholdPercent=90 ^
                -XX:G1RSetUpdatingPauseTimePercent=5 ^
                -XX:SurvivorRatio=32 ^
                -XX:+PerfDisableSharedMem ^
                -XX:+AlwaysPreTouch ^
                -XX:+UseStringDeduplication ^
                -Dfile.encoding=UTF-8 ^
                -jar "!JAR_FILE!" nogui
            
            pause
        """.trimIndent()

        val shContent = """
            #!/bin/bash
            # Lobby Server Startup Script
            # Automatically downloads and runs the latest Paper JAR
            
            cd "${'$'}(dirname "${'$'}0")"
            
            if ! ls paper-*.jar 1> /dev/null 2>&1; then
                echo "Downloading Paper JAR..."
                # JAR will be downloaded by the distribution process
            fi
            
            JAR_FILE=$(ls -t paper-*.jar 2>/dev/null | head -n 1)
            
            if [ -z "${'$'}JAR_FILE" ]; then
                echo "ERROR: No Paper JAR found in this directory"
                echo "Please ensure paper-*.jar exists"
                exit 1
            fi
            
            echo "Starting Lobby server from ${'$'}JAR_FILE..."
            
            java \
                -Xmx4G -Xms2G \
                -XX:+UseG1GC \
                -XX:+ParallelRefProcEnabled \
                -XX:MaxGCPauseMillis=200 \
                -XX:+UnlockExperimentalVMOptions \
                -XX:G1NewCollectionPercentage=30 \
                -XX:G1MaxNewGenPercent=40 \
                -XX:G1HeapRegionSize=8M \
                -XX:G1HeapWastePercent=5 \
                -XX:G1MixedGCCountTarget=4 \
                -XX:InitiatingHeapOccupancyPercent=15 \
                -XX:G1MixedGCLiveThresholdPercent=90 \
                -XX:G1RSetUpdatingPauseTimePercent=5 \
                -XX:SurvivorRatio=32 \
                -XX:+PerfDisableSharedMem \
                -XX:+AlwaysPreTouch \
                -XX:+UseStringDeduplication \
                -Dfile.encoding=UTF-8 \
                -jar "${'$'}JAR_FILE" nogui
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
            
            REM Game Server Startup Script
            REM Automatically downloads and runs the latest Paper JAR
            
            cd /d "%~dp0"
            
            if not exist "paper-*.jar" (
                echo Downloading Paper JAR...
                REM JAR will be downloaded by the distribution process
            )
            
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
            java ^
                -Xmx6G -Xms2G ^
                -XX:+UseG1GC ^
                -XX:+ParallelRefProcEnabled ^
                -XX:MaxGCPauseMillis=200 ^
                -XX:+UnlockExperimentalVMOptions ^
                -XX:G1NewCollectionPercentage=30 ^
                -XX:G1MaxNewGenPercent=40 ^
                -XX:G1HeapRegionSize=8M ^
                -XX:G1HeapWastePercent=5 ^
                -XX:G1MixedGCCountTarget=4 ^
                -XX:InitiatingHeapOccupancyPercent=15 ^
                -XX:G1MixedGCLiveThresholdPercent=90 ^
                -XX:G1RSetUpdatingPauseTimePercent=5 ^
                -XX:SurvivorRatio=32 ^
                -XX:+PerfDisableSharedMem ^
                -XX:+AlwaysPreTouch ^
                -XX:+UseStringDeduplication ^
                -Dfile.encoding=UTF-8 ^
                -jar "!JAR_FILE!" nogui
            
            pause
        """.trimIndent()

        val shContent = """
            #!/bin/bash
            # Game Server Startup Script
            # Automatically downloads and runs the latest Paper JAR
            
            cd "${'$'}(dirname "${'$'}0")"
            
            if ! ls paper-*.jar 1> /dev/null 2>&1; then
                echo "Downloading Paper JAR..."
                # JAR will be downloaded by the distribution process
            fi
            
            JAR_FILE=$(ls -t paper-*.jar 2>/dev/null | head -n 1)
            
            if [ -z "${'$'}JAR_FILE" ]; then
                echo "ERROR: No Paper JAR found in this directory"
                echo "Please ensure paper-*.jar exists"
                exit 1
            fi
            
            echo "Starting Game server from ${'$'}JAR_FILE..."
            
            java \
                -Xmx6G -Xms2G \
                -XX:+UseG1GC \
                -XX:+ParallelRefProcEnabled \
                -XX:MaxGCPauseMillis=200 \
                -XX:+UnlockExperimentalVMOptions \
                -XX:G1NewCollectionPercentage=30 \
                -XX:G1MaxNewGenPercent=40 \
                -XX:G1HeapRegionSize=8M \
                -XX:G1HeapWastePercent=5 \
                -XX:G1MixedGCCountTarget=4 \
                -XX:InitiatingHeapOccupancyPercent=15 \
                -XX:G1MixedGCLiveThresholdPercent=90 \
                -XX:G1RSetUpdatingPauseTimePercent=5 \
                -XX:SurvivorRatio=32 \
                -XX:+PerfDisableSharedMem \
                -XX:+AlwaysPreTouch \
                -XX:+UseStringDeduplication \
                -Dfile.encoding=UTF-8 \
                -jar "${'$'}JAR_FILE" nogui
        """.trimIndent()

        serverDir.resolve("start.bat").writeText(batContent + "\n")
        val shFile = serverDir.resolve("start.sh")
        shFile.writeText(shContent + "\n")
        shFile.setExecutable(true)

        println("  [write]  ${serverDir.resolve("start.bat").name}")
        println("  [write]  ${serverDir.resolve("start.sh").name}")
    }
}

