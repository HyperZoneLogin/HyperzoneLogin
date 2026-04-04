package icu.h2l.login.manager

import icu.h2l.login.HyperZoneLoginMain

class LoginServerManager {
    fun shouldOfflineHost(hostName: String): Boolean {
        return HyperZoneLoginMain.getOfflineMatchConfig().hostMatch.start.any { it.startsWith(hostName) }
    }
} 