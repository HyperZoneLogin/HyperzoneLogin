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

package icu.h2l.login.auth.offline.mail

import icu.h2l.login.auth.offline.config.OfflineAuthConfig
import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.nio.charset.StandardCharsets
import java.util.*

class JakartaMailOfflineAuthEmailSender(
    private val config: OfflineAuthConfig.SmtpConfig,
    private val serverName: String,
    private val logger: java.util.logging.Logger
) : OfflineAuthEmailSender {
    override fun sendRecoveryCode(message: OfflineAuthEmailSender.RecoveryCodeMailMessage): OfflineAuthEmailSender.DeliveryResult {
        if (config.host.isBlank()) {
            return OfflineAuthEmailSender.DeliveryResult(false, "SMTP host 未配置")
        }
        if (config.fromAddress.isBlank()) {
            return OfflineAuthEmailSender.DeliveryResult(false, "SMTP fromAddress 未配置")
        }
        if (config.auth && config.username.isBlank()) {
            return OfflineAuthEmailSender.DeliveryResult(false, "SMTP username 未配置")
        }

        return runCatching {
            val session = Session.getInstance(createProperties(), buildAuthenticator())
            val mail = MimeMessage(session).apply {
                setFrom(InternetAddress(config.fromAddress, config.fromName, StandardCharsets.UTF_8.name()))
                setRecipients(Message.RecipientType.TO, arrayOf(InternetAddress(message.email, message.playerName, StandardCharsets.UTF_8.name())))
                subject = renderTemplate(config.recoverySubject, message)
                setText(renderTemplate(config.recoveryBody, message), StandardCharsets.UTF_8.name())
                sentDate = java.util.Date()
            }
            Transport.send(mail)
            logger.info("离线找回邮件已发送到 ${message.email}")
            OfflineAuthEmailSender.DeliveryResult(true)
        }.getOrElse { throwable ->
            OfflineAuthEmailSender.DeliveryResult(false, throwable.message ?: throwable.javaClass.simpleName)
        }
    }

    private fun createProperties(): Properties {
        return Properties().apply {
            setProperty("mail.transport.protocol", "smtp")
            setProperty("mail.smtp.host", config.host)
            setProperty("mail.smtp.port", config.port.toString())
            setProperty("mail.smtp.auth", config.auth.toString())
            setProperty("mail.smtp.starttls.enable", config.startTls.toString())
            setProperty("mail.smtp.ssl.enable", config.ssl.toString())
            setProperty("mail.smtp.connectiontimeout", config.connectionTimeoutMillis.toString())
            setProperty("mail.smtp.timeout", config.readTimeoutMillis.toString())
            setProperty("mail.smtp.writetimeout", config.writeTimeoutMillis.toString())
        }
    }

    private fun buildAuthenticator(): Authenticator? {
        if (!config.auth) {
            return null
        }
        return object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(config.username, config.password)
            }
        }
    }

    private fun renderTemplate(template: String, message: OfflineAuthEmailSender.RecoveryCodeMailMessage): String {
        return template
            .replace("%server%", serverName)
            .replace("%player%", message.playerName)
            .replace("%email%", message.email)
            .replace("%code%", message.recoveryCode)
            .replace("%minutes%", message.expireMinutes.toString())
            .replace("\\n", "\n")
    }
}

