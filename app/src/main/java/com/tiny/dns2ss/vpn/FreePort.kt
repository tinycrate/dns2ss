package com.tiny.dns2ss.vpn

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.random.Random

object FreePort {
    fun forTcpAndUdp(): Int {
        repeat(64) {
            val port = Random.nextInt(20000, 60001)
            if (canBindTcp(port) && canBindUdp(port)) return port
        }
        throw IllegalStateException("Could not find a free local TCP/UDP port")
    }

    private fun canBindTcp(port: Int): Boolean = try {
        ServerSocket().use { it.reuseAddress = true; it.bind(InetSocketAddress("127.0.0.1", port)); true }
    } catch (_: Throwable) {
        false
    }

    private fun canBindUdp(port: Int): Boolean = try {
        DatagramSocket(port, InetAddress.getByName("127.0.0.1")).use { true }
    } catch (_: Throwable) {
        false
    }
}
