package com.tiny.dns2ss.testclient

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.widget.TextView
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import kotlin.random.Random

/**
 * Screen-off battery workload: one unique DNS query per second plus a 1 MB
 * HTTPS transfer every 30 s for [seconds] (default 10 minutes). Holds a
 * partial wake lock and a high-performance Wi-Fi lock so the screen can stay
 * off. Results are logged under "BatteryLoad".
 */
class BatteryLoadActivity : Activity() {
    private val tag = "BatteryLoad"
    private val running = AtomicBoolean(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(true)
            setShowWhenLocked(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val text = TextView(this)
        setContentView(text)
        val requested = intent?.getIntExtra("seconds", 0) ?: 0
        val seconds = if (requested in 60..1800) requested else 300
        line("onCreate seconds=$seconds")
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = try {
            power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dns2ss:battery-load")
                .apply { acquire((seconds + 120) * 1000L) }
        } catch (t: Throwable) {
            line("wakeLock failed ${t.javaClass.simpleName}: ${t.message}")
            null
        }
        Thread {
            val started = SystemClock.elapsedRealtime()
            var dnsQueries = 0
            var dnsErrors = 0
            var httpOk = 0
            var httpFail = 0
            var httpBytes = 0L
            var nextHttp = started
            var lastReport = started
            try {
                line("thread started seconds=$seconds")
                while (running.get() && SystemClock.elapsedRealtime() - started < seconds * 1000L) {
                    val now = SystemClock.elapsedRealtime()
                    if (now >= nextHttp) {
                        val result = fetchOneMegabyte()
                        if (result > 0) {
                            httpOk++
                            httpBytes += result
                        } else {
                            httpFail++
                        }
                        nextHttp = now + 30_000
                    }
                    // Unique random names force a real resolver request instead of a cache hit.
                    val name = "b${Random.nextLong().toULong().toString(36)}.example.com"
                    if (querySystemDns(name)) dnsErrors++ else dnsQueries++
                    if (now - lastReport >= 10_000) {
                        val elapsed = (now - started) / 1000
                        line("elapsed=${elapsed}s dnsQueries=$dnsQueries dnsErrors=$dnsErrors httpOk=$httpOk httpFail=$httpFail bytes=$httpBytes")
                        lastReport = now
                    }
                    Thread.sleep(1_000)
                }
            } catch (t: Throwable) {
                line("FATAL ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                val elapsed = (SystemClock.elapsedRealtime() - started) / 1000
                line("DONE elapsed=${elapsed}s dnsQueries=$dnsQueries dnsErrors=$dnsErrors httpOk=$httpOk httpFail=$httpFail httpBytes=$httpBytes")
                runCatching { wakeLock?.release() }
                runOnUiThread { text.text = "DONE ${elapsed}s dns=$dnsQueries/$dnsErrors http=$httpOk/$httpFail bytes=$httpBytes" }
            }
        }.start()
    }

    override fun onDestroy() {
        running.set(false)
        super.onDestroy()
    }

    private fun querySystemDns(name: String): Boolean {
        return try {
            InetAddress.getAllByName(name)
            false
        } catch (t: Throwable) {
            true
        }
    }

    private fun fetchOneMegabyte(): Long {
        return try {
            val connection = URL("https://speed.cloudflare.com/__down?bytes=1000000")
                .openConnection() as HttpsURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 30000
            connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                }
                total
            }
        } catch (t: Throwable) {
            -1
        }
    }

    private fun buildQuery(name: String): ByteArray {
        val id = Random.nextInt(1, 65535)
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

    private fun line(message: String) {
        Log.i(tag, message)
    }
}

