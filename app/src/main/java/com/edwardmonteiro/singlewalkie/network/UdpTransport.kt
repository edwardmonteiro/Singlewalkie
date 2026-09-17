package com.edwardmonteiro.singlewalkie.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException

class UdpTransport(
    private val listenPort: Int,
    private val onPacket: (ByteArray, InetAddress, Int) -> Unit,
) {
    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private var receiverThread: Thread? = null

    fun start() {
        if (running) return
        val newSocket = DatagramSocket(null).apply {
            reuseAddress = true
            soTimeout = 1000
            bind(InetSocketAddress(listenPort))
        }
        socket = newSocket
        running = true
        receiverThread = Thread({ receiveLoop(newSocket) }, "walkie-udp-rx").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun send(data: ByteArray, address: InetAddress, port: Int) {
        val activeSocket = socket ?: return
        if (!running) return
        activeSocket.send(DatagramPacket(data, data.size, address, port))
    }

    private fun receiveLoop(activeSocket: DatagramSocket) {
        val buffer = ByteArray(1500)
        while (running) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                activeSocket.receive(packet)
                onPacket(packet.data.copyOfRange(packet.offset, packet.offset + packet.length), packet.address, packet.port)
            } catch (_: SocketTimeoutException) {
                // Wake periodically so close() can stop the loop cleanly.
            } catch (_: SocketException) {
                break
            } catch (_: Throwable) {
                // A malformed packet must never kill the receiver loop.
            }
        }
    }

    fun close() {
        running = false
        socket?.close()
        socket = null
        receiverThread?.join(250)
        receiverThread = null
    }
}
