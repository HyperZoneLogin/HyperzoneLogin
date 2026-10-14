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

/**
 * `CHAT_SESSION_UPDATE`（serverbound）包体结构描述。
 *
 * ── 参考实现 ─────────────────────────────────────────────────────────────
 * ViaVersion `Protocol1_20_3To1_20_5`：
 * ```
 * wrapper.read(Types.UUID);          // sessionId
 * wrapper.read(Types.PROFILE_KEY);   // { long expiresAt, byte[] publicKey, byte[] signature }
 * ```
 * Velocity `RemoteChatSession`：`readUuid(buf)` + `readPlayerKey(version, buf)`，
 * 而 `ProtocolUtils.writePlayerKey` 的字节布局是
 * `writeLong(expiry) / writeByteArray(pubKey) / writeByteArray(signature)`。
 * 三者一致，因此 1.19.3 至今（含 26.x）共用同一结构。
 *
 * ── 兼容性约定 ───────────────────────────────────────────────────────────
 * 结构如果未来发生变化（字段顺序、增删字段、长度前缀方式），不要修改本类语义，
 * 而是新增一个布局实例并在 [ChatSessionUpdateLayoutResolver] 中按版本区间登记，
 * 使旧版本继续按旧结构匹配。
 *
 * 字段含义：
 * - [uuidPartitionCount]：包体开头的裸 UUID 个数（每个 16 字节，非长度前缀）。
 * - [hasProfileKey]：是否在 UUID 之后跟随一个 ProfileKey。
 * - [profileKeyExpiryPresent]：ProfileKey 是否以 `long` 毫秒时间戳开头。
 * - [lengthPrefixedKey] / [lengthPrefixedSignature]：publicKey / signature 是否带 VarInt 长度前缀。
 * - [minKeyLength] / [maxKeyLength]：publicKey 合理长度区间（RSA-2048 SPKI 约 294 字节）。
 * - [minSignatureLength] / [maxSignatureLength]：signature 合理长度区间（RSA-2048 约 256 字节）。
 * - [treatUuidAsFreshSession]：UUID 是否应为客户端新生成的随机 sessionId（用于内容可信度判定）。
 */
data class ChatSessionUpdatePacketLayout(
    val name: String,
    val uuidPartitionCount: Int,
    val hasProfileKey: Boolean,
    val profileKeyExpiryPresent: Boolean,
    val lengthPrefixedKey: Boolean,
    val minKeyLength: Int,
    val maxKeyLength: Int,
    val lengthPrefixedSignature: Boolean,
    val minSignatureLength: Int,
    val maxSignatureLength: Int,
    val treatUuidAsFreshSession: Boolean,
) {
    /** 裸 UUID 占用的固定字节数。 */
    val uuidBytes: Int
        get() = uuidPartitionCount * UUID_BYTES

    /** ProfileKey 时间戳字段占用的固定字节数。 */
    val expiryBytes: Int
        get() = if (hasProfileKey && profileKeyExpiryPresent) LONG_BYTES else 0

    /** 包体在「空内容」情况下的最小长度：UUID + 时间戳 + key/signature 的长度前缀。 */
    val minimalBodyBytes: Int
        get() {
            val prefixBytes = if (hasProfileKey) {
                (if (lengthPrefixedKey) VARINT_MIN_BYTES else 0) +
                        (if (lengthPrefixedSignature) VARINT_MIN_BYTES else 0)
            } else {
                0
            }
            return uuidBytes + expiryBytes + prefixBytes
        }

    companion object {
        /** 单个 UUID 的固定字节数（两个 long）。 */
        const val UUID_BYTES: Int = 16

        /** 单个 long 的字节数。 */
        const val LONG_BYTES: Int = 8

        /** VarInt 至少占用的字节数。 */
        const val VARINT_MIN_BYTES: Int = 1

        /**
         * 1.19.3 起（含 1.19.4 / 1.20.x / 1.21.x / 26.x）通用的结构：
         * `UUID(16) + expiresAt(long) + VarInt(pubKey) + VarInt(signature)`。
         */
        val UUID_THEN_PROFILE_KEY: ChatSessionUpdatePacketLayout = ChatSessionUpdatePacketLayout(
            name = "uuid-then-profile-key",
            uuidPartitionCount = 1,
            hasProfileKey = true,
            profileKeyExpiryPresent = true,
            lengthPrefixedKey = true,
            minKeyLength = 32,
            maxKeyLength = 4_096,
            lengthPrefixedSignature = true,
            minSignatureLength = 32,
            maxSignatureLength = 4_096,
            treatUuidAsFreshSession = true,
        )
    }
}
