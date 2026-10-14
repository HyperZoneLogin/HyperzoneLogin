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

package icu.h2l.login.util.chat

import io.netty.buffer.ByteBuf

/**
 * 基于**包体结构 + 内容合理性**的匹配器（兜底通道）。
 *
 * 不依赖数据包 id，而是按 [ChatSessionUpdatePacketLayout] 逐字段解析包体：
 * `UUID(16) + expiresAt(long) + VarInt(pubKey) + VarInt(signature)`，
 * 并要求解析后**恰好**消费完整个包体。任何一步不成立即判为「不是目标包」，原样放行。
 *
 * 该策略对「无关包」天然安全：其他包的字节流几乎不可能同时满足
 * 「长度自洽 + 时间戳落在合理区间 + 两个长度前缀数组恰好填满缓冲区」。
 *
 * 所有解析失败一律吞掉并返回 false —— 待匹配的包里绝大多数都不是目标包，
 * 解析失败是预期路径而非异常。
 */
object StructureChatSessionUpdateMatcher : ChatSessionUpdatePacketMatcher {

    override val name: String = "structure"

    /**
     * 时间戳合理区间：2015-01-01 ~ 2100-01-01（毫秒）。
     * 用于排除把任意 long 误读成 expiry 的情况。
     */
    private const val MIN_EXPIRY_MILLIS: Long = 1_420_070_400_000L
    private const val MAX_EXPIRY_MILLIS: Long = 4_102_444_800_000L

    override fun matches(view: ChatSessionPacketView): Boolean {
        if (!view.isPayloadPresentable) {
            return false
        }

        // 版本未知时回退到通用结构：所有已知版本共用同一布局，未知的新版本大概率也沿用，
        // 这正是「为未来变化预留」的兜底路径；解析本身已足够严格，误判风险可控。
        val layout = ChatSessionUpdateLayoutResolver.resolve(view.protocolVersion)
            ?: ChatSessionUpdateLayoutResolver.fallbackLayout

        val buf = view.payload.duplicate()
        return runCatching { parse(buf, layout) }.getOrDefault(false)
    }

    /**
     * 严格解析 [buf] 并按 [layout] 校验；成功时返回 true 且缓冲区恰好读完。
     * 任何长度/内容异常通过返回 false 表达，不抛异常给调用方。
     */
    private fun parse(buf: ByteBuf, layout: ChatSessionUpdatePacketLayout): Boolean {
        if (buf.readableBytes() < layout.minimalBodyBytes) {
            return false
        }

        // ── sessionId：裸 UUID，仅做「非全零」的内容合理性检查 ──
        var uuidAllZero = true
        repeat(layout.uuidPartitionCount) {
            if (buf.readableBytes() < ChatSessionUpdatePacketLayout.UUID_BYTES) {
                return false
            }
            val msb = buf.readLong()
            val lsb = buf.readLong()
            if (msb != 0L || lsb != 0L) {
                uuidAllZero = false
            }
        }
        if (layout.treatUuidAsFreshSession && uuidAllZero) {
            return false
        }

        // ── ProfileKey ──
        if (layout.hasProfileKey) {
            if (layout.profileKeyExpiryPresent) {
                if (buf.readableBytes() < ChatSessionUpdatePacketLayout.LONG_BYTES) {
                    return false
                }
                val expiry = buf.readLong()
                if (expiry < MIN_EXPIRY_MILLIS || expiry > MAX_EXPIRY_MILLIS) {
                    return false
                }
            }

            if (!consumeLengthPrefixedArray(
                    buf,
                    lengthPrefixed = layout.lengthPrefixedKey,
                    minLength = layout.minKeyLength,
                    maxLength = layout.maxKeyLength,
                )
            ) {
                return false
            }

            if (!consumeLengthPrefixedArray(
                    buf,
                    lengthPrefixed = layout.lengthPrefixedSignature,
                    minLength = layout.minSignatureLength,
                    maxLength = layout.maxSignatureLength,
                )
            ) {
                return false
            }
        }

        // 结构匹配的关键判据：包体必须被恰好消费完，不能有多余尾字节。
        return !buf.isReadable
    }

    /**
     * 消费一个「长度前缀 + 内容」的字节数组，校验长度落在 [minLength, maxLength] 内。
     *
     * @param lengthPrefixed false 时表示该字段无长度前缀（预留的布局变体）。
     */
    private fun consumeLengthPrefixedArray(
        buf: ByteBuf,
        lengthPrefixed: Boolean,
        minLength: Int,
        maxLength: Int,
    ): Boolean {
        val declared: Int = if (lengthPrefixed) {
            if (!buf.isReadable) {
                return false
            }
            readVarInt(buf) ?: return false
        } else {
            buf.readableBytes()
        }

        if (declared < minLength || declared > maxLength) {
            return false
        }
        if (buf.readableBytes() < declared) {
            return false
        }

        buf.skipBytes(declared)
        return true
    }

    /**
     * 读取 Minecraft VarInt（最多 5 字节）。
     *
     * @return 解析结果；字节不足或超长时返回 null。
     */
    private fun readVarInt(buf: ByteBuf): Int? {
        var value = 0
        var position = 0
        while (position < MAX_VARINT_BYTES) {
            if (!buf.isReadable) {
                return null
            }

            val current = buf.readByte().toInt()
            value = value or ((current and 0x7F) shl (position * 7))
            if (current and 0x80 == 0) {
                return value
            }
            position++
        }
        return null
    }

    private const val MAX_VARINT_BYTES = 5
}
