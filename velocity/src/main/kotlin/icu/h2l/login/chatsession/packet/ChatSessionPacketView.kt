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

package icu.h2l.login.chatsession.packet

import com.velocitypowered.api.network.ProtocolVersion
import io.netty.buffer.ByteBuf

/**
 * 单个待匹配数据包的只读视图。
 *
 * 视图由 outbound 侧 handler 构造：`payload` 已经越过了包头 VarInt，readerIndex
 * 指向包体第一个字节；`packetId` 是包头解析出的原始 id，供基于 id 的匹配器使用。
 *
 * 约定：匹配器 **不得** 修改 [payload] 的 readerIndex；如需读取必须先 `duplicate()`。
 * 视图对未知协议版本保持宽容，[protocolVersion] 可能为 null。
 */
class ChatSessionPacketView(
    /** 数据包原始内容（已越过包头 VarInt）。 */
    val payload: ByteBuf,
    /** 包头 VarInt 声明的数据包 id。 */
    val packetId: Int,
    /** 该连接协商的协议版本；无法判定时为 null。 */
    val protocolVersion: ProtocolVersion?,
) {
    /** 包体是否还有可读字节。 */
    val isPayloadPresentable: Boolean
        get() = payload.isReadable

    /** 包体剩余字节数（即越过包头后的长度）。 */
    val payloadLength: Int
        get() = payload.readableBytes()

    companion object {
        /** VarInt 的最大字节数。 */
        private const val MAX_VARINT_BYTES = 5

        /**
         * 从原始数据包构造视图。
         *
         * 会 `duplicate()` 两次：一次用于解析包头 id，一次作为 [payload]。
         * 原始 [packet] 的 readerIndex 不受影响，调用方仍需自行管理其生命周期。
         *
         * @return 视图；包体为空或包头 VarInt 非法时返回 null。
         */
        fun of(packet: ByteBuf, protocolVersion: ProtocolVersion?): ChatSessionPacketView? {
            if (!packet.isReadable) {
                return null
            }

            // 以 readerIndex 为基准计算包头长度，避免假设缓冲区从 0 开始读。
            val start = packet.readerIndex()
            val header = packet.duplicate()
            val packetId = readVarInt(header) ?: return null
            val headerLength = header.readerIndex() - start

            val body = packet.duplicate().apply { skipBytes(headerLength) }

            return ChatSessionPacketView(
                payload = body,
                packetId = packetId,
                protocolVersion = protocolVersion,
            )
        }

        /** 读取 VarInt；字节不足或超长时返回 null。 */
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
    }
}
