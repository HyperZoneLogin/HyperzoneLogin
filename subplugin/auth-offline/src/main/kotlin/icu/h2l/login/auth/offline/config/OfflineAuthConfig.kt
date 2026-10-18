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

package icu.h2l.login.auth.offline.config

import org.spongepowered.configurate.objectmapping.ConfigSerializable
import org.spongepowered.configurate.objectmapping.meta.Comment

@ConfigSerializable
class OfflineAuthConfig {
    // 密码规则
    @Comment("config.offline.password")
    val password = PasswordPolicy()

    // 登录保护
    @Comment("config.offline.login")
    val login = LoginProtection()

    // 邮箱与找回
    @Comment("config.offline.email")
    val email = EmailConfig()

    // 提示
    @Comment("config.offline.prompt")
    val prompt = PromptConfig()

    // 会话自动登录
    @Comment("config.offline.session")
    val session = SessionConfig()

    // 透传离线 UUID
    @Comment("config.offline.pass-offline-uuid")
    var passOfflineUuidToProfileResolve: Boolean = true

    // TOTP 二步验证
    @Comment("config.offline.totp")
    val totp = TotpConfig()

    @ConfigSerializable
    class PasswordPolicy {
        // 最短密码长度
        @Comment("config.offline.password.min-length")
        val minLength = 6

        // 最长密码长度
        @Comment("config.offline.password.max-length")
        val maxLength = 64

        // 禁止密码含用户名
        @Comment("config.offline.password.deny-name")
        val denyNameInPassword = true
    }

    @ConfigSerializable
    class LoginProtection {
        // 输错多少次锁定登录
        @Comment("config.offline.login.max-attempts")
        val maxAttempts = 5

        // 锁定冷却时间（秒）
        @Comment("config.offline.login.block-seconds")
        val blockSeconds = 300
    }

    @ConfigSerializable
    class EmailConfig {
        // 启用邮箱命令
        @Comment("config.offline.email.enabled")
        val enabled = true

        // 恢复码投递模式：LOG 或 SMTP
        @Comment("config.offline.email.delivery-mode")
        val deliveryMode = "LOG"

        // 恢复码长度
        @Comment("config.offline.email.code-length")
        val recoveryCodeLength = 6

        // 恢复码有效期（分钟）
        @Comment("config.offline.email.code-expire")
        val recoveryCodeExpireMinutes = 15

        // 请求恢复邮件冷却（秒）
        @Comment("config.offline.email.recovery-cooldown")
        val recoveryCooldownSeconds = 120

        // 单个恢复码允许输错次数
        @Comment("config.offline.email.max-verify-attempts")
        val maxCodeVerifyAttempts = 3

        // 恢复码校验成功后，允许修改密码的时间窗口（分钟）
        @Comment("config.offline.email.reset-window")
        val resetPasswordWindowMinutes = 10

        // 恢复邮件模板与 SMTP 配置
        @Comment("config.offline.email.smtp")
        val smtp = SmtpConfig()
    }

    @ConfigSerializable
    class SmtpConfig {
        // 邮件显示的服务器名称
        @Comment("config.offline.smtp.server-name")
        val serverName = "HyperZoneLogin"

        // SMTP 服务器地址
        @Comment("config.offline.smtp.host")
        val host = "smtp.example.com"

        // SMTP 端口
        @Comment("config.offline.smtp.port")
        val port = 587

        // 是否启用 SMTP 认证
        @Comment("config.offline.smtp.auth")
        val auth = true

        // SMTP 用户名
        @Comment("config.offline.smtp.username")
        val username = "noreply@example.com"

        // SMTP 密码或应用专用密码
        @Comment("config.offline.smtp.password")
        val password = "change-me"

        // 是否启用 STARTTLS
        @Comment("config.offline.smtp.start-tls")
        val startTls = true

        // 是否直接使用 SSL
        @Comment("config.offline.smtp.ssl")
        val ssl = false

        // 连接超时（毫秒）
        @Comment("config.offline.smtp.connection-timeout")
        val connectionTimeoutMillis = 10000

        // 读取超时（毫秒）
        @Comment("config.offline.smtp.read-timeout")
        val readTimeoutMillis = 10000

        // 写入超时（毫秒）
        @Comment("config.offline.smtp.write-timeout")
        val writeTimeoutMillis = 10000

        // 发件人邮箱
        @Comment("config.offline.smtp.from-address")
        val fromAddress = "noreply@example.com"

        // 发件人名称
        @Comment("config.offline.smtp.from-name")
        val fromName = "HyperZoneLogin"

        // 恢复邮件主题，支持占位符：%server%、%player%
        @Comment("config.offline.smtp.recovery-subject")
        val recoverySubject = "[%server%] 账号密码找回验证码"

        // 恢复邮件正文，支持占位符：%server%、%player%、%email%、%code%、%minutes%。使用 \n 表示换行
        @Comment("config.offline.smtp.recovery-body")
        val recoveryBody =
            "你好，%player%。\\n\\n你在 %server% 请求了离线账号密码找回。\\n验证码：%code%\\n有效期：%minutes% 分钟\\n\\n如果不是你本人操作，请忽略这封邮件。"
    }

    @ConfigSerializable
    class PromptConfig {
        // 首次进入邮箱找回提示
        @Comment("config.offline.prompt.show-recovery-hint")
        val showRecoveryHint = true
    }

    @ConfigSerializable
    class SessionConfig {
        // 短期会话自动登录
        @Comment("config.offline.session.enabled")
        val enabled = false

        // 会话有效期（分钟）
        @Comment("config.offline.session.expire-minutes")
        val expireMinutes = 30

        // 会话与玩家 IP 绑定
        @Comment("config.offline.session.bind-ip")
        val bindIp = true

        // 注册成功后立刻签发会话
        @Comment("config.offline.session.issue-on-register")
        val issueOnRegister = true
    }

    @ConfigSerializable
    class TotpConfig {
        // 启用 TOTP（二步验证功能）
        @Comment("config.offline.totp.enabled")
        val enabled = true

        // 在验证器 App 中显示的名称
        @Comment("config.offline.totp.issuer")
        val issuer = "HyperZoneLogin"

        // 待确认 TOTP 密钥的有效期（分钟）
        @Comment("config.offline.totp.pending-expire")
        val pendingExpireMinutes = 10

        // 允许 短期会话 跳过二次验证
        @Comment("config.offline.totp.allow-session-bypass")
        val allowSessionBypass = false
    }
}
