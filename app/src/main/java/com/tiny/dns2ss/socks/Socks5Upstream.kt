package com.tiny.dns2ss.socks

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

class Socks5TcpUpstream(
    private val socksHost: String,
    private val socksPort: Int,
    private val connectTimeoutMs: Int = 10_000,
) {
    fun connect(target: Socks5Address): Socket {
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(socksHost, socksPort), connectTimeoutMs)
            socket.soTimeout = connectTimeoutMs
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

            output.write(byteArrayOf(Socks5Protocol.VERSION.toByte(), 1, Socks5Protocol.AUTH_NONE.toByte()))
            output.flush()
            val version = input.readUnsignedByte()
            val method = input.readUnsignedByte()
            require(version == Socks5Protocol.VERSION && method == Socks5Protocol.AUTH_NONE) {
                "Local SOCKS server rejected no-auth"
            }

            output.writeByte(Socks5Protocol.VERSION)
            output.writeByte(Socks5Protocol.CMD_CONNECT)
            output.writeByte(0)
            output.write(target.toBytes())
            output.flush()

            val reply = readReply(input)
            if (reply.code != Socks5Protocol.REP_SUCCESS) {
                throw IllegalStateException("SOCKS CONNECT failed: ${reply.code}")
            }
            socket.soTimeout = 0
            return socket
        } catch (t: Throwable) {
            runCatching { socket.close() }
            throw t
        }
    }

    private fun readReply(input: DataInputStream): Socks5Reply {
        val version = input.readUnsignedByte()
        require(version == Socks5Protocol.VERSION) { "Bad SOCKS reply version" }
        val code = input.readUnsignedByte()
        input.readUnsignedByte()
        val address = Socks5Address.read(input)
        return Socks5Reply(code, address)
    }
}

class Socks5UdpClient(
    private val socksHost: String,
    private val socksPort: Int,
    private val connectTimeoutMs: Int = 10_000,
) : Closeable {
    private val control = Socket()
    private lateinit var udp: DatagramSocket
    private lateinit var relay: InetSocketAddress
    private lateinit var input: DataInputStream
    private lateinit var output: DataOutputStream

    fun open() {
        control.tcpNoDelay = true
        control.connect(InetSocketAddress(socksHost, socksPort), connectTimeoutMs)
        control.soTimeout = connectTimeoutMs
        input = DataInputStream(BufferedInputStream(control.getInputStream()))
        output = DataOutputStream(BufferedOutputStream(control.getOutputStream()))

        output.write(byteArrayOf(Socks5Protocol.VERSION.toByte(), 1, Socks5Protocol.AUTH_NONE.toByte()))
        output.flush()
        val version = input.readUnsignedByte()
        val method = input.readUnsignedByte()
        require(version == Socks5Protocol.VERSION && method == Socks5Protocol.AUTH_NONE) {
            "Local SOCKS server rejected no-auth"
        }

        output.writeByte(Socks5Protocol.VERSION)
        output.writeByte(Socks5Protocol.CMD_UDP_ASSOCIATE)
        output.writeByte(0)
        output.write(Socks5Address.fromHostPort("0.0.0.0", 0).toBytes())
        output.flush()

        val reply = readReply()
        if (reply.code != Socks5Protocol.REP_SUCCESS) {
            throw IllegalStateException("SOCKS UDP ASSOCIATE failed: ${reply.code}")
        }
        val address = reply.address.socketAddress()
        val host = if (address.address.isAnyLocalAddress) "127.0.0.1" else address.address.hostAddress
        relay = InetSocketAddress(host, address.port)
        udp = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        control.soTimeout = 0
    }

    fun send(target: Socks5Address, payload: ByteArray) {
        val packet = Socks5Address.buildUdp(target, payload)
        udp.send(DatagramPacket(packet, packet.size, relay))
    }

    fun receive(): Pair<Socks5Address, ByteArray>? {
        val buffer = ByteArray(65535)
        val packet = DatagramPacket(buffer, buffer.size)
        udp.receive(packet)
        return Socks5Address.parseUdp(packet.data, packet.length)
    }

    override fun close() {
        runCatching { udp.close() }
        runCatching { control.close() }
    }

    private fun readReply(): Socks5Reply {
        val version = input.readUnsignedByte()
        require(version == Socks5Protocol.VERSION) { "Bad SOCKS reply version" }
        val code = input.readUnsignedByte()
        input.readUnsignedByte()
        val address = Socks5Address.read(input)
        return Socks5Reply(code, address)
    }
}

data class Socks5Reply(val code: Int, val address: Socks5Address)
