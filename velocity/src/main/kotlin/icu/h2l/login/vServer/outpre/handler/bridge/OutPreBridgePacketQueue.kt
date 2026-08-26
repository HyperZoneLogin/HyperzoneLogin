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

package icu.h2l.login.vServer.outpre.handler.bridge

import com.velocitypowered.proxy.connection.MinecraftConnection
import com.velocitypowered.proxy.protocol.MinecraftPacket
import icu.h2l.login.vServer.outpre.OutPreBackendBridge
import io.netty.channel.EventLoop
import io.netty.util.ReferenceCountUtil
import io.netty.util.ReferenceCounted
import java.util.ArrayDeque

/**
 * 客户端 → 后端的包转发队列。
 *
 * 当后端 bridge 尚未到达所需阶段时，客户端发送的包会被缓存在队列中；
 * bridge 阶段推进后自动 flush 到后端连接。
 *
 * 队列自动注册 bridge 的 phase listener，无需外部手动触发 flush。
 *
 * @param bridge 后端桥接连接
 * @param clientEventLoop 客户端连接的 event loop，保证线程安全
 * @param onPhaseAdvanced 可选回调，在每次 phase 推进且 flush 之前调用（如发送等待区命令）
 */
class OutPreBridgePacketQueue(
    private val bridge: OutPreBackendBridge,
    clientEventLoop: EventLoop,
    private val onPhaseAdvanced: (() -> Unit)? = null,
) {
    private data class PendingWrite(
        val requiredPhase: OutPreBackendBridge.Phase,
        val write: (MinecraftConnection) -> Unit,
        val release: () -> Unit = {},
    )

    private val queue = ArrayDeque<PendingWrite>()

    init {
        bridge.addPhaseListener { _ ->
            clientEventLoop.execute {
                val phase = bridge.phase()
                if (phase == OutPreBackendBridge.Phase.CLOSING || phase == OutPreBackendBridge.Phase.CLOSED) {
                    clear()
                    return@execute
                }
                onPhaseAdvanced?.invoke()
                flush()
            }
        }
    }

    /**
     * 发送或缓存一个自定义写操作。
     */
    fun send(requiredPhase: OutPreBackendBridge.Phase, action: (MinecraftConnection) -> Unit) {
        dispatch(PendingWrite(requiredPhase, action))
    }

    /**
     * 发送或缓存一个 [MinecraftPacket]，自动处理引用计数。
     */
    fun sendPacket(requiredPhase: OutPreBackendBridge.Phase, packet: MinecraftPacket) {
        if (packet is ReferenceCounted) {
            sendRetained(
                requiredPhase = requiredPhase,
                writeNow = { connection -> connection.write(ReferenceCountUtil.retain(packet) as MinecraftPacket) },
                retainForQueue = { ReferenceCountUtil.retain(packet) as MinecraftPacket },
                writer = { connection, queuedPacket -> connection.write(queuedPacket as MinecraftPacket) },
            )
            return
        }
        send(requiredPhase) { it.write(packet) }
    }

    /**
     * 发送或缓存一个引用计数对象（ByteBuf / 带 ByteBuf 的 packet）。
     *
     * 调用方负责提供 retain/write 回调以正确管理生命周期。
     */
    fun sendRetained(
        requiredPhase: OutPreBackendBridge.Phase,
        writeNow: (MinecraftConnection) -> Unit,
        retainForQueue: () -> Any,
        writer: (MinecraftConnection, Any) -> Unit,
    ) {
        if (bridge.canForwardClientPackets(requiredPhase)) {
            writeNow(bridge.ensureConnected())
            return
        }

        if (!bridge.canQueueClientPackets()) {
            return
        }

        val retained = retainForQueue()
        enqueue(
            PendingWrite(
                requiredPhase = requiredPhase,
                write = { writer(it, retained) },
                release = { ReferenceCountUtil.safeRelease(retained) },
            )
        )
    }

    /**
     * flush 所有已到达所需阶段的缓存包到后端连接。
     */
    fun flush() {
        val readyWrites = ArrayList<PendingWrite>()
        synchronized(queue) {
            if (queue.isEmpty()) return

            val remaining = ArrayDeque<PendingWrite>()
            while (queue.isNotEmpty()) {
                val next = queue.removeFirst()
                if (bridge.canForwardClientPackets(next.requiredPhase)) {
                    readyWrites += next
                } else {
                    remaining += next
                }
            }
            queue += remaining
        }

        if (readyWrites.isEmpty()) return

        val backend = bridge.ensureConnected()
        readyWrites.forEach { it.write(backend) }
    }

    /**
     * 释放所有缓存包（连接断开时调用）。
     */
    fun clear() {
        while (true) {
            val next = synchronized(queue) {
                if (queue.isEmpty()) null else queue.removeFirst()
            } ?: return
            next.release()
        }
    }

    private fun dispatch(pendingWrite: PendingWrite) {
        if (bridge.canForwardClientPackets(pendingWrite.requiredPhase)) {
            pendingWrite.write(bridge.ensureConnected())
            return
        }

        if (!bridge.canQueueClientPackets()) {
            pendingWrite.release()
            return
        }

        enqueue(pendingWrite)
    }

    private fun enqueue(pendingWrite: PendingWrite) {
        synchronized(queue) {
            queue += pendingWrite
        }
    }
}
