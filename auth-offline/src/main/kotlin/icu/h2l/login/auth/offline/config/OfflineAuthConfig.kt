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
    @Comment("密码规则")
    @JvmField
    val password = PasswordPolicy()

    @Comment("登录保护")
    @JvmField
    val login = LoginProtection()

    @Comment("邮箱与找回")
    @JvmField
    val email = EmailConfig()

    @Comment("提示")
    @JvmField
    val prompt = PromptConfig()

    @Comment("会话自动登录")
    @JvmField
    val session = SessionConfig()

    @ConfigSerializable
    class PasswordPolicy {
        @Comment("最短密码长度")
        val minLength = 6

        @Comment("最长密码长度")
        val maxLength = 64

        @Comment("是否禁止密码中包含用户名")
        val denyNameInPassword = true
    }

    @ConfigSerializable
    class LoginProtection {
        @Comment("连续输错多少次后临时锁定登录")
        val maxAttempts = 5

        @Comment("触发锁定后的冷却秒数")
        val blockSeconds = 300
    }

    @ConfigSerializable
    class EmailConfig {
        @Comment("是否启用邮箱相关命令")
        val enabled = true

        @Comment("恢复码投递模式：LOG 或 SMTP")
        val deliveryMode = "LOG"

        @Comment("恢复码长度")
        val recoveryCodeLength = 6

        @Comment("恢复码有效期（分钟）")
        val recoveryCodeExpireMinutes = 15

        @Comment("重复请求恢复邮件冷却（秒）")
        val recoveryCooldownSeconds = 120

        @Comment("单个恢复码允许输错次数")
        val maxCodeVerifyAttempts = 3

        @Comment("恢复码校验成功后，允许修改密码的时间窗口（分钟）")
        val resetPasswordWindowMinutes = 10

        @Comment("恢复邮件模板与 SMTP 配置")
        @JvmField
        val smtp = SmtpConfig()
    }

    @ConfigSerializable
    class SmtpConfig {
        @Comment("邮件里显示的服务器名称")
        val serverName = "HyperZoneLogin"

        @Comment("SMTP 服务器地址")
        val host = "smtp.example.com"

        @Comment("SMTP 端口")
        val port = 587

        @Comment("是否启用 SMTP 认证")
        val auth = true

        @Comment("SMTP 用户名")
        val username = "noreply@example.com"

        @Comment("SMTP 密码或应用专用密码")
        val password = "change-me"

        @Comment("是否启用 STARTTLS")
        val startTls = true

        @Comment("是否直接使用 SSL")
        val ssl = false

        @Comment("连接超时（毫秒）")
        val connectionTimeoutMillis = 10000

        @Comment("读取超时（毫秒）")
        val readTimeoutMillis = 10000

        @Comment("写入超时（毫秒）")
        val writeTimeoutMillis = 10000

        @Comment("发件人邮箱")
        val fromAddress = "noreply@example.com"

        @Comment("发件人名称")
        val fromName = "HyperZoneLogin"

        @Comment("恢复邮件主题，支持占位符：%server%、%player%")
        val recoverySubject = "[%server%] 账号密码找回验证码"

        @Comment(
            "恢复邮件正文，支持占位符：%server%、%player%、%email%、%code%、%minutes%。使用 \\n 表示换行"
        )
        val recoveryBody = "你好，%player%。\\n\\n你在 %server% 请求了离线账号密码找回。\\n验证码：%code%\\n有效期：%minutes% 分钟\\n\\n如果不是你本人操作，请忽略这封邮件。"
    }

    @ConfigSerializable
    class PromptConfig {
        @Comment("首次进入认证阶段时是否额外展示邮箱找回提示")
        val showRecoveryHint = true
    }

    @ConfigSerializable
    class SessionConfig {
        @Comment("是否启用短期会话自动登录")
        val enabled = true

        @Comment("会话有效期（分钟）")
        val expireMinutes = 30

        @Comment("是否把会话与玩家 IP 绑定")
        val bindIp = true

        @Comment("注册成功后是否立刻签发会话")
        val issueOnRegister = true
    }
}

