package icu.h2l.login.util

import icu.h2l.login.HyperZoneLoginMain
import icu.h2l.login.type.OfflineUUIDType
import icu.h2l.login.util.uuid.PCL2UUIDUtil
import java.nio.charset.StandardCharsets
import java.util.*

object ExtraUuidUtils {
    private val zero: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")

    fun matchType(holderUUID: UUID?, name: String): OfflineUUIDType {
        if (holderUUID == null) {
            return OfflineUUIDType.ZERO
        }
        return when {
            HyperZoneLoginMain.getOfflineMatchConfig().uuidMatch.offline && holderUUID == getNormalOfflineUUID(name) -> OfflineUUIDType.OFFLINE
            HyperZoneLoginMain.getOfflineMatchConfig().uuidMatch.pcl2.enable && PCL2UUIDUtil.isPCL2UUID(
                holderUUID,
                name
            ) -> OfflineUUIDType.PCL

            HyperZoneLoginMain.getOfflineMatchConfig().uuidMatch.zero && holderUUID == zero -> OfflineUUIDType.ZERO

            else -> OfflineUUIDType.UNKNOWN
        }
    }

    private fun getNormalOfflineUUID(username: String): UUID {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:$username").toByteArray(StandardCharsets.UTF_8))
    }
} 