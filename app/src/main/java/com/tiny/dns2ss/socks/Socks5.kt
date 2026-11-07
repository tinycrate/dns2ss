package com.tiny.dns2ss.socks

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/** A SOCKS5 target address, preserving the wire representation. */
data class Socks5Address(
    val atyp: Int,
    val host: String,
    val port: Int,
    val encodedAddress: ByteArray,
) {
    fun toBytes(): ByteArray {
        val out = ByteArray(1 + encodedAddress.size + 2)
        out[0] = atyp.toByte()
        System.arraycopy(encodedAddress, 0, out, 1, encodedAddress.size)
        out[1 + encodedAddress.size] = ((port ushr 8) and 0xff).toByte()
        out[2 + encodedAddress.size] = (port and 0xff).toByte()
        return out
    }

    fun socketAddress(): InetSocketAddress {
        val address = when (atyp) {
            ATYP_IPV4, ATYP_IPV6 -> InetAddress.getByAddress(encodedAddress)
            else -> InetAddress.getByName(host)
        }
        return InetSocketAddress(address, port)
    }

    companion object {
        const val ATYP_IPV4 = 1
        const val ATYP_DOMAIN = 3
        const val ATYP_IPV6 = 4

        fun fromInetAddress(address: InetAddress, port: Int): Socks5Address {
            val raw = address.address
            return when (address) {
                is Inet4Address -> Socks5Address(ATYP_IPV4, address.hostAddress ?: "0.0.0.0", port, raw)
                is Inet6Address -> Socks5Address(ATYP_IPV6, address.hostAddress ?: "::", port, raw)
                else -> domain(address.hostAddress ?: "0.0.0.0", port)
            }
        }

        fun fromHostPort(host: String, port: Int): Socks5Address {
            if (host.contains(':')) {
                val address = InetAddress.getByName(host)
                return fromInetAddress(address, port)
            }
            val parts = host.split('.')
            if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) {
                val raw = ByteArray(4) { parts[it].toInt().toByte() }
                return Socks5Address(ATYP_IPV4, host, port, raw)
            }
            return domain(host, port)
        }

        fun domain(host: String, port: Int): Socks5Address {
            val bytes = host.toByteArray(StandardCharsets.UTF_8)
            require(bytes.size in 1..255) { "SOCKS5 domain is too long" }
            val encoded = ByteArray(1 + bytes.size)
            encoded[0] = bytes.size.toByte()
            System.arraycopy(bytes, 0, encoded, 1, bytes.size)
            return Socks5Address(ATYP_DOMAIN, host, port, encoded)
        }

        fun read(input: DataInputStream): Socks5Address {
            val atyp = input.readUnsignedByte()
            return when (atyp) {
                ATYP_IPV4 -> {
                    val raw = ByteArray(4)
                    input.readFully(raw)
                    Socks5Address(atyp, InetAddress.getByAddress(raw).hostAddress ?: "0.0.0.0", input.readUnsignedShort(), raw)
                }
                ATYP_IPV6 -> {
                    val raw = ByteArray(16)
                    input.readFully(raw)
                    Socks5Address(atyp, InetAddress.getByAddress(raw).hostAddress ?: "::", input.readUnsignedShort(), raw)
                }
                ATYP_DOMAIN -> {
                    val length = input.readUnsignedByte()
                    require(length > 0) { "Empty SOCKS5 domain" }
                    val encoded = ByteArray(1 + length)
                    encoded[0] = length.toByte()
                    input.readFully(encoded, 1, length)
                    val host = String(encoded, 1, length, StandardCharsets.UTF_8)
                    Socks5Address(atyp, host, input.readUnsignedShort(), encoded)
                }
                else -> throw IllegalArgumentException("Unsupported SOCKS5 address type $atyp")
            }
        }

        fun parseUdp(packet: ByteArray, length: Int): Pair<Socks5Address, ByteArray>? {
            if (length < 4) return null
            if (packet[0].toInt() != 0 || packet[1].toInt() != 0) return null
            if (packet[2].toInt() != 0) return null // FRAG is not supported
            var offset = 3
            val atyp = packet[offset++].toInt() and 0xff
            val encoded: ByteArray
            val host: String
            when (atyp) {
                ATYP_IPV4 -> {
                    if (length < offset + 4 + 2) return null
                    encoded = packet.copyOfRange(offset, offset + 4)
                    host = InetAddress.getByAddress(encoded).hostAddress ?: "0.0.0.0"
                    offset += 4
                }
                ATYP_IPV6 -> {
                    if (length < offset + 16 + 2) return null
                    encoded = packet.copyOfRange(offset, offset + 16)
                    host = InetAddress.getByAddress(encoded).hostAddress ?: "::"
                    offset += 16
                }
                ATYP_DOMAIN -> {
                    if (length < offset + 1) return null
                    val domainLength = packet[offset].toInt() and 0xff
                    offset++
                    if (length < offset + domainLength + 2) return null
                    host = String(packet, offset, domainLength, StandardCharsets.UTF_8)
                    encoded = ByteArray(1 + domainLength)
                    encoded[0] = domainLength.toByte()
                    System.arraycopy(packet, offset, encoded, 1, domainLength)
                    offset += domainLength
                }
                else -> return null
            }
            if (length < offset + 2) return null
            val port = ((packet[offset].toInt() and 0xff) shl 8) or (packet[offset + 1].toInt() and 0xff)
            offset += 2
            if (offset > length) return null
            val payload = packet.copyOfRange(offset, length)
            return Socks5Address(atyp, host, port, encoded) to payload
        }

        fun buildUdp(source: Socks5Address, payload: ByteArray): ByteArray {
            val address = source.toBytes()
            val out = ByteArray(3 + address.size + payload.size)
            out[0] = 0
            out[1] = 0
            out[2] = 0
            System.arraycopy(address, 0, out, 3, address.size)
            System.arraycopy(payload, 0, out, 3 + address.size, payload.size)
            return out
        }
    }
}

object Socks5Protocol {
    const val VERSION = 0x05
    const val AUTH_NONE = 0x00
    const val AUTH_USER_PASS = 0x02
    const val AUTH_UNACCEPTABLE = 0xff

    const val CMD_CONNECT = 0x01
    const val CMD_BIND = 0x02
    const val CMD_UDP_ASSOCIATE = 0x03

    const val REP_SUCCESS = 0x00
    const val REP_GENERAL_FAILURE = 0x01
    const val REP_NOT_ALLOWED = 0x02
    const val REP_NETWORK_UNREACHABLE = 0x03
    const val REP_HOST_UNREACHABLE = 0x04
    const val REP_CONNECTION_REFUSED = 0x05
    const val REP_TTL_EXPIRED = 0x06
    const val REP_COMMAND_NOT_SUPPORTED = 0x07
    const val REP_ADDRESS_NOT_SUPPORTED = 0x08

    fun writeMethod(output: DataOutputStream, method: Int) {
        output.writeByte(VERSION)
        output.writeByte(method)
        output.flush()
    }

    fun writeReply(output: DataOutputStream, reply: Int, address: Socks5Address? = null) {
        val target = address ?: Socks5Address(1, "0.0.0.0", 0, byteArrayOf(0, 0, 0, 0))
        output.writeByte(VERSION)
        output.writeByte(reply)
        output.writeByte(0)
        output.write(target.toBytes())
        output.flush()
    }

    fun readReply(input: DataInputStream): Triple<Int, Socks5Address, Unit> {
        val version = input.readUnsignedByte()
        require(version == VERSION) { "Bad SOCKS5 reply version $version" }
        val reply = input.readUnsignedByte()
        input.readUnsignedByte()
        val address = Socks5Address.read(input)
        return Triple(reply, address, Unit)
    }

    fun greet(input: DataInputStream, output: DataOutputStream, expectedUser: String?, expectedPassword: String?): Boolean {
        val version = input.readUnsignedByte()
        require(version == VERSION) { "Bad SOCKS5 greeting version $version" }
        val count = input.readUnsignedByte()
        require(count in 1..255) { "Bad SOCKS5 method count" }
        val methods = ByteArray(count)
        input.readFully(methods)
        val hasUserPass = methods.any { (it.toInt() and 0xff) == AUTH_USER_PASS }
        val hasNone = methods.any { (it.toInt() and 0xff) == AUTH_NONE }

        if (expectedUser != null && expectedPassword != null) {
            if (!hasUserPass) {
                writeMethod(output, AUTH_UNACCEPTABLE)
                return false
            }
            writeMethod(output, AUTH_USER_PASS)
            val authVersion = input.readUnsignedByte()
            if (authVersion != 0x01) return false
            val userLength = input.readUnsignedByte()
            val userBytes = ByteArray(userLength)
            input.readFully(userBytes)
            val passLength = input.readUnsignedByte()
            val passBytes = ByteArray(passLength)
            input.readFully(passBytes)
            val user = String(userBytes, StandardCharsets.UTF_8)
            val password = String(passBytes, StandardCharsets.UTF_8)
            val ok = user == expectedUser && password == expectedPassword
            output.writeByte(0x01)
            output.writeByte(if (ok) 0x00 else 0x01)
            output.flush()
            return ok
        }

        if (!hasNone) {
            writeMethod(output, AUTH_UNACCEPTABLE)
            return false
        }
        writeMethod(output, AUTH_NONE)
        return true
    }

    fun request(input: DataInputStream): Pair<Int, Socks5Address> {
        val version = input.readUnsignedByte()
        require(version == VERSION) { "Bad SOCKS5 request version $version" }
        val command = input.readUnsignedByte()
        input.readUnsignedByte()
        val address = Socks5Address.read(input)
        return command to address
    }
}
