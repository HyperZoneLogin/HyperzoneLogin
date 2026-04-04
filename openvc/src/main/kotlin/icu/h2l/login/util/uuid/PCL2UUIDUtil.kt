package icu.h2l.login.util.uuid

import icu.h2l.login.HyperZoneLoginMain
import java.util.*

object PCL2UUIDUtil {
    //        PCL2/Plain Craft Launcher 2/Modules/Minecraft/ModLaunch.vb 1120行
//
//
//    前几个方法主要是模拟和复现PCL2的生成过程
    fun getUUID(name: String): UUID {
        return UUID.fromString(toUUID(getStringUUID(name)))
    }

    fun getUUID(name: String, slim: Boolean): UUID {
        return UUID.fromString(toUUID(adjustUUIDForSkin(getStringUUID(name), slim)))
    }

    fun getUUID_Fast(name: String, slim: Boolean): UUID {
        return UUID.fromString(toUUID(adjustUUIDForSkin(getStringUUID(name), slim)))
    }

//    原函数

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

//    简化函数

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
//        换用布尔更快
        return isSlimSkin(uuid.replace("-", "")) == isSlim
    }

    private fun isSlimSkin(uuid: String): Boolean {
        if (uuid.length != 32) return false
//        7位由于算法原因一定为0,这里不取以加速
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

    //    下面的方法才是主要使用的方法
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

    //    粗略匹配函数
    fun isPCL2UUID(
        uuid: UUID,
        name: String,
        hashMatch: Boolean = HyperZoneLoginMain.getOfflineMatchConfig().uuidMatch.pcl2.hash,
        slimMatch: Boolean = HyperZoneLoginMain.getOfflineMatchConfig().uuidMatch.pcl2.slim
    ): Boolean {
        if (!hashMatch) return hasPCL2Info(uuid, name)

        val strRemove = uuid.toString().replace("-", "")
//      进行需要哈希运算的深度匹配
        val hash = fillZeroTo16(java.lang.Long.toHexString(hash(name)))
//       需要截取后十五位做比较
        val matchBasic = strRemove.substring(17, 32) == hash.substring(1, 16)
        if (matchBasic) return true
//        后续进行slim模型的匹配
        if (!slimMatch) return false
        val isSlim = isSlimSkin(strRemove)
//        取最后四位
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
//        判断前11位是不是0
        if (strRemove.substring(0, 12) != "000000000000") return false
//        判断 13~15位是不是0
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
//                由于原算法是加,所以我们匹配要反着来
                val nextNum = lastPart.toLong(16) - 1
//                这里进匹配后会变成小写,所以要小写
                currentUuid = "${currentUuid.substring(0, 27)}${nextNum.toString(16).lowercase().padStart(5, '0')}"
            }
        }
        return currentUuid
    }


    @JvmStatic
    fun main(args: Array<String>) {
//        ksqeib:00000000-0000-3006-998f-555b0138dc4d
        println("ksqeib:${getUUID("ksqeib")}")
        println("rule:${isPCL2UUID(getUUID("ksqeib"), "ksqeib", true)}")
        println("rule:${isPCL2UUID(getUUID("ksqeibksqeib"), "ksqeibksqeib", true)}")
        println("rule:${isPCL2UUID(getUUID("ksqeibksqeib", true), "ksqeibksqeib", true)}")


        println("Diamonds:${getUUID("Diamonds")}")
        println("Diamonds:${getUUID("Diamonds", false)}")
        println("Diamonds:${getUUID_Fast("Diamonds", false)}")
        println("Diamonds:${getUUID("Diamonds", true)}")
        println("rule:${isPCL2UUID(getUUID("Diamonds", true), "Diamonds", true)}")
        println("rule:${isPCL2UUID(getUUID("Diamonds", false), "Diamonds", true)}")
    }
} 