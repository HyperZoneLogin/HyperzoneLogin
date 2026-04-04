package icu.h2l.login.config

import org.spongepowered.configurate.objectmapping.ConfigSerializable
import org.spongepowered.configurate.objectmapping.meta.Comment

@ConfigSerializable
class OfflineMatchConfig {

    @Comment("是否允许进行匹配")
    val enable = false

    @Comment("UUID匹配设定")
    val uuidMatch = UUIDMatch()

    @Comment("Host匹配设定")
    val hostMatch = HostMatch()

    @ConfigSerializable
    class UUIDMatch {
        @ConfigSerializable
        class PCL2 {
            @Comment("PCL2的UUID匹配")
            val enable = true

            @Comment("PCL2的UUID进行哈希计算匹配")
            val hash = true

            @Comment("PCL2的苗条模型UUID匹配")
            val slim = true
        }

        @Comment("是否允许全0的UUID(Zalith) 匹配为离线")
        val zero = true

        @Comment("是否允许默认uuid生成方法 匹配为离线")
        val offline = true

        @Comment("关于PCL2启动器匹配的细节设定")
        val pcl2 = PCL2()
    }

    @ConfigSerializable
    class HostMatch {
        val start = listOf("offline", "o-")
    }

    @Comment("高级设定")
    @JvmField
    val advanced = Advanced()

    @ConfigSerializable
    class Advanced {
        @Comment("开启debug模式")
        @JvmField
        val debug = true
    }
}
