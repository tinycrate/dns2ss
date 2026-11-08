package com.tiny.dns2ss.testclient

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.random.Random

/**
 * Same-device benchmark client. DNS is sent to the active network's resolver
 * (the virtual resolver while dns2ss is running), so Fast mode is measured
 * through the system-DNS route rather than bypassing it. HTTP bytes are set
 * with the "bytes" intent extra; the default is 5 MB.
 */
class BenchActivity : Activity() {
    private val tag = "Bench"
    private val output = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this)
        setContentView(text)
        Thread {
            try {
                runBench()
            } catch (t: Throwable) {
                line("FATAL ${t.javaClass.simpleName}: ${t.message}")
            }
            runOnUiThread { text.text = output.toString() }
        }.start()
    }

    private fun runBench() {
        line("BENCH START")
        line("device=${android.os.Build.MODEL} sdk=${android.os.Build.VERSION.SDK_INT}")
        line("network=${networkSummary()}")
        val dnsServer = systemDnsServer()
        line("system DNS server=${dnsServer ?: "unavailable"}")
        if (dnsServer != null) {
            // Common day-to-day destinations, shuffled to reduce ordering bias.
            val domains = listOf(
                "www.google.com",
                "www.youtube.com",
                "www.facebook.com",
                "www.amazon.com",
                "www.wikipedia.org",
                "www.cloudflare.com",
                "www.github.com",
                "www.microsoft.com",
                "www.apple.com",
                "www.netflix.com",
                "www.instagram.com",
                "www.whatsapp.com",
            ).shuffled()
            domains.forEach { dnsBench(dnsServer, it) }
        }
        tcpConnectBench()
        ntpBench()
        systemDnsBench()
        val httpRuns = intent?.getIntExtra("httpRuns", 1)?.coerceIn(1, 5) ?: 1
        repeat(httpRuns) { index ->
            httpBench(index)
            if (index + 1 < httpRuns) Thread.sleep(1000)
        }
        line("BENCH DONE")
    }

    private fun dnsBench(server: String, name: String) {
        var cold = -1L
        repeat(4) { index ->
            val ms = queryUdp(server, name)
            if (index == 0) {
                cold = ms
                line("DNS cold $name ${fmt(ms)}")
            } else {
                line("DNS warm $name #$index ${fmt(ms)}")
            }
        }
        if (cold < 0) line("DNS cold $name FAIL")
    }

    private fun queryUdp(server: String, name: String): Long {
        val id = Random.nextInt(1, 65535)
        val query = buildQuery(id, name)
        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 5000
                socket.connect(InetSocketAddress(InetAddress.getByName(server), 53))
                val started = System.nanoTime()
                socket.send(DatagramPacket(query, query.size))
                val buffer = ByteArray(4096)
                val response = DatagramPacket(buffer, buffer.size)
                socket.receive(response)
                val elapsed = (System.nanoTime() - started) / 1_000_000
                val rcode = buffer[3].toInt() and 0x0f
                if (response.length < 12 || rcode != 0) {
                    line("DNS $name rcode=$rcode len=${response.length}")
                }
                elapsed
            }
        } catch (t: Throwable) {
            line("DNS $name FAIL ${t.javaClass.simpleName}")
            -1
        }
    }

    private fun tcpConnectBench() {
        repeat(4) { index ->
            try {
                Socket().use { socket ->
                    val started = System.nanoTime()
                    socket.connect(InetSocketAddress("1.1.1.1", 443), 5000)
                    val ms = (System.nanoTime() - started) / 1_000_000
                    line("TCP connect 1.1.1.1:443 #$index ${fmt(ms)}")
                }
            } catch (t: Throwable) {
                line("TCP connect #$index FAIL ${t.javaClass.simpleName}")
            }
        }
    }

    private fun ntpBench() {
        repeat(3) { index ->
            try {
                DatagramSocket().use { socket ->
                    socket.soTimeout = 3000
                    val request = ByteArray(48)
                    request[0] = 0x23 // NTPv4 client
                    val packet = DatagramPacket(request, request.size, InetAddress.getByName("162.159.200.1"), 123)
                    val started = System.nanoTime()
                    socket.send(packet)
                    val receive = DatagramPacket(ByteArray(48), 48)
                    socket.receive(receive)
                    val ms = (System.nanoTime() - started) / 1_000_000
                    line("NTP #$index ${fmt(ms)}")
                }
            } catch (t: Throwable) {
                line("NTP #$index FAIL ${t.javaClass.simpleName}")
            }
        }
    }

    private fun systemDnsBench() {
        repeat(3) { index ->
            try {
                val started = System.nanoTime()
                InetAddress.getAllByName("example.com")
                val ms = (System.nanoTime() - started) / 1_000_000
                line("System DNS #$index ${fmt(ms)}")
            } catch (t: Throwable) {
                line("System DNS #$index FAIL ${t.javaClass.simpleName}")
            }
        }
    }

    private fun httpBench(index: Int) {
        val bytes = intent?.getIntExtra("bytes", 5_000_000) ?: 5_000_000
        val url = "https://speed.cloudflare.com/__down?bytes=$bytes"
        try {
            val connection = URL(url).openConnection() as HttpsURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 60000
            connection.requestMethod = "GET"
            val started = System.nanoTime()
            val total = connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                }
                total
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            val mbps = if (elapsedMs > 0) (total * 8.0 / 1_000_000.0) / (elapsedMs / 1000.0) else 0.0
            line("HTTP #$index $total bytes ${fmt(elapsedMs)} ${String.format("%.2f", mbps)} Mbps")
        } catch (t: Throwable) {
            line("HTTP #$index FAIL ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun buildQuery(id: Int, name: String): ByteArray {
        val out = ArrayList<Byte>()
        out.add(((id ushr 8) and 0xff).toByte())
        out.add((id and 0xff).toByte())
        out.add(0x01); out.add(0x00)
        out.add(0x00); out.add(0x01)
        out.add(0x00); out.add(0x00)
        out.add(0x00); out.add(0x00)
        out.add(0x00); out.add(0x00)
        for (label in name.trim('.').split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.add(bytes.size.toByte())
            bytes.forEach { out.add(it) }
        }
        out.add(0)
        out.add(0x00); out.add(0x01)
        out.add(0x00); out.add(0x01)
        return out.toByteArray()
    }

    private fun networkSummary(): String {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return "unknown"
            val caps = cm.getNetworkCapabilities(network) ?: return "unknown"
            val transport = when {
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
            "$transport linkDown=${caps.linkDownstreamBandwidthKbps}kbps"
        } catch (t: Throwable) {
            "unknown"
        }
    }

    private fun systemDnsServer(): String? {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return null
            val properties = cm.getLinkProperties(network) ?: return null
            properties.dnsServers.firstOrNull { it.address.size == 4 }?.hostAddress
        } catch (t: Throwable) {
            null
        }
    }

    private fun fmt(ms: Long): String = if (ms < 0) "FAIL" else "${ms}ms"

    private fun line(message: String) {
        Log.i(tag, message)
        synchronized(output) { output.appendLine(message) }
    }
}
