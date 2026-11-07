package com.tiny.dns2ss.testclient

import android.app.Activity
import android.os.Bundle
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.random.Random

class TestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread({
            runTests()
            runOnUiThread { finish() }
        }, "testclient").start()
    }

    private fun runTests() {
        val fakeUdp = "203.0.113.53"
        val fakeTcp = "203.0.113.54"
        val name = "example.com"
        try {
            udpReuse(fakeUdp, name, 6)
        } catch (t: Throwable) {
            Log.i(TAG, "UDP fake-resolver reuse FAIL ${t.javaClass.simpleName}: ${t.message}")
        }
        try {
            repeat(2) { index ->
                val id = Random.nextInt(1, 65535)
                val query = buildQuery(id, name)
                val started = System.nanoTime()
                val ok = udpQuery(fakeUdp, query, id)
                val elapsed = (System.nanoTime() - started) / 1_000_000
                Log.i(TAG, "UDP fake-resolver fresh #$index ${elapsed}ms -> ${if (ok) "OK" else "FAIL"}")
            }
        } catch (t: Throwable) {
            Log.i(TAG, "UDP fake-resolver fresh FAIL ${t.javaClass.simpleName}: ${t.message}")
        }
        try {
            val id = Random.nextInt(1, 65535)
            val query = buildQuery(id, name)
            val ok = tcpQuery(fakeTcp, query, id)
            Log.i(TAG, "TCP fake-resolver $fakeTcp -> ${if (ok) "OK" else "FAIL"}")
        } catch (t: Throwable) {
            Log.i(TAG, "TCP fake-resolver FAIL ${t.javaClass.simpleName}: ${t.message}")
        }
        try {
            val ok = udpNtp("216.239.35.0")
            Log.i(TAG, "UDP non-DNS NTP -> ${if (ok) "OK" else "FAIL"}")
        } catch (t: Throwable) {
            Log.i(TAG, "UDP non-DNS NTP FAIL ${t.javaClass.simpleName}: ${t.message}")
        }
        try {
            val connection = (URL("https://example.com/").openConnection() as HttpsURLConnection)
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            val code = connection.responseCode
            connection.inputStream.close()
            Log.i(TAG, "HTTPS example.com -> HTTP $code")
        } catch (t: Throwable) {
            Log.i(TAG, "HTTPS example.com FAIL ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun udpQuery(host: String, query: ByteArray, id: Int): Boolean {
        DatagramSocket().use { socket ->
            socket.soTimeout = 4000
            val address = InetSocketAddress(InetAddress.getByName(host), 53)
            socket.connect(address)
            return exchange(socket, query, id)
        }
    }

    private fun udpReuse(host: String, name: String, count: Int) {
        DatagramSocket().use { socket ->
            socket.soTimeout = 4000
            val address = InetSocketAddress(InetAddress.getByName(host), 53)
            socket.connect(address)
            repeat(count) { index ->
                val id = Random.nextInt(1, 65535)
                val query = buildQuery(id, name)
                val started = System.nanoTime()
                val ok = exchange(socket, query, id)
                val elapsed = (System.nanoTime() - started) / 1_000_000
                Log.i(TAG, "UDP fake-resolver reuse #$index ${elapsed}ms -> ${if (ok) "OK" else "FAIL"}")
            }
        }
    }

    private fun exchange(socket: DatagramSocket, query: ByteArray, id: Int): Boolean {
        socket.send(DatagramPacket(query, query.size))
        val buffer = ByteArray(4096)
        val response = DatagramPacket(buffer, buffer.size)
        socket.receive(response)
        if (response.length < 12) return false
        val responseId = ((buffer[0].toInt() and 0xff) shl 8) or (buffer[1].toInt() and 0xff)
        val rcode = buffer[3].toInt() and 0x0f
        return responseId == id && rcode == 0
    }

    private fun tcpQuery(host: String, query: ByteArray, id: Int): Boolean {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, 53), 4000)
            socket.soTimeout = 4000
            val output = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())
            output.writeShort(query.size)
            output.write(query)
            output.flush()
            val length = input.readUnsignedShort()
            if (length < 12) return false
            val response = ByteArray(length)
            input.readFully(response)
            val responseId = ((response[0].toInt() and 0xff) shl 8) or (response[1].toInt() and 0xff)
            val rcode = response[3].toInt() and 0x0f
            return responseId == id && rcode == 0
        }
    }

    private fun udpNtp(host: String): Boolean {
        DatagramSocket().use { socket ->
            socket.soTimeout = 4000
            val request = ByteArray(48)
            request[0] = 0x1b
            val address = InetSocketAddress(InetAddress.getByName(host), 123)
            socket.send(DatagramPacket(request, request.size, address))
            val response = DatagramPacket(ByteArray(512), 512)
            socket.receive(response)
            return response.length >= 48
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
        for (label in name.split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        out.write(0x00); out.write(0x01)
        out.write(0x00); out.write(0x01)
        return out.toByteArray()
    }

    companion object {
        private const val TAG = "TestClient"
    }
}
