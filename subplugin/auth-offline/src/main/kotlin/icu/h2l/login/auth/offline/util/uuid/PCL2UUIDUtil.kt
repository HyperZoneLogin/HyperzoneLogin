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

package icu.h2l.login.auth.offline.util.uuid

import icu.h2l.login.auth.offline.config.AuthOfflineConfigLoader
import icu.h2l.login.auth.offline.util.ExtraUuidUtils
import java.util.*

object PCL2UUIDUtil {
    // PCL2/Plain Craft Launcher 2 UUID utilities (ported)

    fun getUUID(name: String): UUID {
        return UUID.fromString(toUUID(getStringUUID(name)))
    }

    fun getUUID(name: String, slim: Boolean): UUID {
        return UUID.fromString(toUUID(adjustUUIDForSkin(getStringUUID(name), slim)))
    }

    fun getUUID_Fast(name: String, slim: Boolean): UUID {
        return UUID.fromString(toUUID(adjustUUIDForSkin(getStringUUID(name), slim)))
    }

    fun adjustUUIDForSkin(uuid: String, isSlim: Boolean): String {
        var currentUuid = uuid
        while (!matchesSkinType(currentUuid, isSlim)) {
            val lastPart = currentUuid.substring(27)
            if (lastPart == "FFFFF") {
                currentUuid = "${currentUuid.substring(0, 27)}00000"
            } else {
                val nextNum = lastPart.toLong(16) + 1
                currentUuid = "${currentUuid.substring(0, 27)}${nextNum.toString(16).uppercase().padStart(5, '0')}"
            }
        }
        return currentUuid
    }

    private fun matchesSkinType(uuid: String, isSlim: Boolean): Boolean {
        return getSkinType(uuid.replace("-", "")) == if (isSlim) "Alex" else "Steve"
    }

    private fun getSkinType(uuid: String): String {
        if (uuid.length != 32) return "Steve"

        val a = uuid[7].toString().toInt(16)
        val b = uuid[15].toString().toInt(16)
        val c = uuid[23].toString().toInt(16)
        val d = uuid[31].toString().toInt(16)

        return if ((a xor b xor c xor d) % 2 == 1) "Alex" else "Steve"
    }

    fun adjustUUIDForSkin_Fast(uuid: String, isSlim: Boolean): String {
        var currentUuid = uuid
        while (!matchesSlim(currentUuid, isSlim)) {
            val lastPart = currentUuid.substring(27)
            if (lastPart == "FFFFF") {
                currentUuid = "${currentUuid.substring(0, 27)}00000"
            } else {
                val nextNum = lastPart.toLong(16) + 1
                currentUuid = "${currentUuid.substring(0, 27)}${nextNum.toString(16).uppercase().padStart(5, '0')}"
            }
        }
        return currentUuid
    }

    private fun matchesSlim(uuid: String, isSlim: Boolean): Boolean {
        return isSlimSkin(uuid.replace("-", "")) == isSlim
    }

    private fun isSlimSkin(uuid: String): Boolean {
        if (uuid.length != 32) return false
        val b = uuid[15].toString().toInt(16)
        val c = uuid[23].toString().toInt(16)
        val d = uuid[31].toString().toInt(16)
        return (0 xor b xor c xor d) % 2 == 1
    }

    private fun getStringUUID(name: String): String = buildString {
        append(fillZeroTo16(Integer.toHexString(name.length)))
        append(fillZeroTo16(java.lang.Long.toHexString(hash(name))))
    }.let(::insertInfo)

    private fun toUUID(no_: String): String = buildString {
        append(no_.substring(0, 8))
        append("-")
        append(no_.substring(8, 12))
        append("-")
        append(no_.substring(12, 16))
        append("-")
        append(no_.substring(16, 20))
        append("-")
        append(no_.substring(20, 32))
    }

    private fun insertInfo(originalUUID: String): String = buildString {
        append(originalUUID.substring(0, 12))
        append('3')
        append(originalUUID.substring(13, 16))
        append('9')
        append(originalUUID.substring(17, 32))
    }

    private fun fillZeroTo16(str: String): String =
        str.take(16).padStart(16, '0')

    private fun hash(str: String): Long {
        var hash = 5381L
        for (element in str) {
            hash = (hash shl 5) xor hash xor element.code.toLong()
        }
        return hash xor -0x5670afe4397bfcd1L
    }

    fun buildInfoPart(name: String): String {
        val partA = fillZeroTo16(Integer.toHexString(name.length)) + "9"
        return partA.replaceRange(12, 13, "3")
    }

    fun isPCL2UUID(uuid: UUID, name: String, hashMatch: Boolean = true, slimMatch: Boolean = true): Boolean {
        val cfg = AuthOfflineConfigLoader.getConfig().match
        val hashEnabled = cfg.uuidMatch.pcl2.hash && hashMatch
        val slimEnabled = cfg.uuidMatch.pcl2.slim && slimMatch
        if (!hashEnabled) return hasPCL2Info(uuid, name)

        val strRemove = uuid.toString().replace("-", "")
        val hash = fillZeroTo16(java.lang.Long.toHexString(hash(name)))
        val matchBasic = strRemove.substring(17, 32) == hash.substring(1, 16)
        if (matchBasic) return true
        if (!slimEnabled) return false
        val isSlim = isSlimSkin(strRemove)
        val hashLast = hash.substring(11, 16)
        val lastPartFinal = adjustUUIDForSkin_Match(strRemove, !isSlim)
        val matchSlim = lastPartFinal.substring(27, 32) == hashLast
        return matchSlim
    }

    private fun hasPCL2Info(uuid: UUID, name: String): Boolean {
        val strRemove = uuid.toString().replace("-", "")
        val info = buildInfoPart(name)
        return strRemove.substring(0, 17) == info
    }

    private fun hasPCL2Info(uuid: UUID): Boolean {
        val strRemove = uuid.toString().replace("-", "")
        if (strRemove.substring(0, 12) != "000000000000") return false
        if (strRemove.substring(13, 15) != "00") return false
        return strRemove[12] == '3' && strRemove[16] == '9'
    }

    fun adjustUUIDForSkin_Match(uuid: String, isSlim: Boolean): String {
        var currentUuid = uuid
        while (!matchesSlim(currentUuid, isSlim)) {
            val lastPart = currentUuid.substring(27)
            if (lastPart == "fffff") {
                currentUuid = "${currentUuid.substring(0, 27)}00000"
            } else {
                val nextNum = lastPart.toLong(16) - 1
                currentUuid = "${currentUuid.substring(0, 27)}${nextNum.toString(16).lowercase().padStart(5, '0')}"
            }
        }
        return currentUuid
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("offline:${ExtraUuidUtils.getNormalOfflineUUID("bieqsk")}")
    }


}


