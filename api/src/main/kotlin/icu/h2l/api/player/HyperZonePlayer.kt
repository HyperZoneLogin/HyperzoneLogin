package icu.h2l.api.player

import com.velocitypowered.api.util.GameProfile
import icu.h2l.api.db.Profile
import net.kyori.adventure.text.Component
import java.util.*

/**
 * HyperZone 登录流程中的统一玩家抽象。
 *
 * 该对象用于封装登入流程中常用的能力，
 * 让各模块不再直接依赖底层 Limbo 会话处理实现。
 */
interface HyperZonePlayer {
    /**
     * 当前玩家的用户名（登录流程内的统一名称）。
     */
    val userName: String

    /**
     * 当前玩家的UUID（登录流程内的统一名称）。
     */
    val uuid: UUID

    /**
     * 判断是否允许执行注册流程。
     *
     * 主要依据：数据库中是否已存在该玩家 Profile。
     */
    fun canRegister(): Boolean

    /**
     * 注册玩家并写入 Profile 表。
     *
     * @return 写入数据库后的 Profile 对象
     */
    fun register(userName: String? = null, uuid: UUID? = null): Profile

    /**
     * 获取当前玩家数据库中对应的 Profile。如果是第一次加入游戏，是获取不到的。只有已注册用户才有。
     *
     * @return Profile，不存在时返回 null
     */
    fun getDBProfile(): Profile?

    /**
     * 当前玩家是否已完成验证。
     */
    fun isVerified(): Boolean

    /**
     * 判断是否允许进行绑定流程。
     */
    fun canBind(): Boolean

    /**
     * 结束玩家验证流程。
     */
    fun overVerify()

    /**
     * 发送消息给玩家。
     */
    fun sendMessage(message: Component)


    /**
     * 获取当前玩家对应的 GameProfile。
     */
    fun getGameProfile(): GameProfile

    /**
     * 获取认证阶段拿到的初始 GameProfile。
     *
     * 该档案通常来自上游认证服务，
     * 可用于后续做皮肤修复、属性补全等处理。
     */
    fun getInitialGameProfile(): GameProfile?

    /**
     * 设置认证阶段拿到的初始 GameProfile。
     */
    fun setInitialGameProfile(profile: GameProfile?)
}