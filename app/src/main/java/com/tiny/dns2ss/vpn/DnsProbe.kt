package com.tiny.dns2ss.vpn

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.random.Random

object DnsProbe {
    data class Result(val ok: Boolean, val message: String, val elapsedMs: Long)

    fun query(tunnelPort: Int, name: String = "example.com"): Result {
        val start = System.currentTimeMillis()
        val id = Random.nextInt(1, 65535)
        val query = buildQuery(id, name)
        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 5000
                socket.send(DatagramPacket(query, query.size, InetSocketAddress(InetAddress.getByName("127.0.0.1"), tunnelPort)))
                val buffer = ByteArray(4096)
                val response = DatagramPacket(buffer, buffer.size)
                socket.receive(response)
                if (response.length < 12) {
                    Result(false, "short DNS response (${response.length} bytes)", System.currentTimeMillis() - start)
                } else {
                    val responseId = ((buffer[0].toInt() and 0xff) shl 8) or (buffer[1].toInt() and 0xff)
                    val rcode = buffer[3].toInt() and 0x0f
                    val answers = ((buffer[6].toInt() and 0xff) shl 8) or (buffer[7].toInt() and 0xff)
                    if (responseId != id) {
                        Result(false, "DNS response id mismatch", System.currentTimeMillis() - start)
                    } else if (rcode != 0) {
                        Result(false, "DNS rcode=$rcode", System.currentTimeMillis() - start)
                    } else {
                        Result(true, "DNS OK (answers=$answers)", System.currentTimeMillis() - start)
                    }
                }
            }
        } catch (t: Throwable) {
            Result(false, "${t.javaClass.simpleName}: ${t.message}", System.currentTimeMillis() - start)
        }
    }

    private fun buildQuery(id: Int, name: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((id ushr 8) and 0xff)
        out.write(id and 0xff)
        out.write(0x01); out.write(0x00)
        out.write(0x00); out.write(0x01)
        out.write(0x00); out.write(0x00)
        out.write(0x00); out.write(0x00)
        out.write(0x00); out.write(0x00)
        for (label in name.trim('.').split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            require(bytes.size in 1..63)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        out.write(0x00); out.write(0x01)
        out.write(0x00); out.write(0x01)
        return out.toByteArray()
    }
}
