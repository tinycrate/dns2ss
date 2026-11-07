package com.tiny.dns2ss.vpn

import android.content.Context
import android.system.Os
import com.tiny.dns2ss.config.SslocalConfigBuilder
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Collections

class SsLocalProcess(
    private val context: Context,
    private val config: com.tiny.dns2ss.config.SslocalConfig,
    private val onUnexpectedExit: (String) -> Unit,
) {
    private var process: Process? = null
    private var reader: Thread? = null
    private var monitor: Thread? = null
    private val logTail = Collections.synchronizedList(mutableListOf<String>())
    @Volatile
    private var stopping = false

    private val configFile: File
        get() = File(context.filesDir, "sslocal.json")

    fun start() {
        check(process == null) { "sslocal already started" }
        stopping = false
        logTail.clear()

        val json = SslocalConfigBuilder.build(config)
        val file = configFile
        file.writeText(json)
        runCatching { Os.chmod(file.absolutePath, 384) } // 0600

        val binary = File(context.applicationInfo.nativeLibraryDir, "libsslocal.so")
        if (!binary.isFile) {
            throw IllegalStateException("sslocal binary not found at ${binary.absolutePath}")
        }

        val builder = ProcessBuilder(binary.absolutePath, "-c", file.absolutePath)
        builder.directory(context.filesDir)
        builder.redirectErrorStream(true)
        builder.environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir

        val started = builder.start()
        process = started
        reader = Thread({ readOutput(started) }, "sslocal-log").apply {
            isDaemon = true
            start()
        }

        // The local listeners bind very quickly. Give sslocal a short window to fail.
        Thread.sleep(450)
        if (!started.isAlive) {
            throw IllegalStateException("sslocal exited during startup\n${snapshotLog()}")
        }

        monitor = Thread({
            try {
                val code = started.waitFor()
                if (!stopping) {
                    onUnexpectedExit("sslocal exited with code $code\n${snapshotLog()}")
                }
            } catch (_: InterruptedException) {
            }
        }, "sslocal-monitor").apply {
            isDaemon = true
            start()
        }
        VpnLog.add("sslocal", "started")
    }

    fun stop() {
        stopping = true
        val current = process
        process = null
        if (current == null) {
            runCatching { configFile.delete() }
            return
        }
        runCatching { current.destroy() }
        runCatching { if (!current.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) current.destroyForcibly() }
        runCatching { current.waitFor(1, java.util.concurrent.TimeUnit.SECONDS) }
        runCatching { reader?.interrupt() }
        runCatching { monitor?.interrupt() }
        runCatching { configFile.delete() }
        VpnLog.add("sslocal", "stopped")
    }

    fun isRunning(): Boolean = process?.isAlive == true

    fun snapshotLog(): String = synchronized(logTail) { logTail.takeLast(40).joinToString("\n") }

    private fun readOutput(started: Process) {
        try {
            BufferedReader(InputStreamReader(started.inputStream)).use { input ->
                while (true) {
                    val line = input.readLine() ?: break
                    synchronized(logTail) {
                        logTail.add(line)
                        while (logTail.size > 200) logTail.removeAt(0)
                    }
                    VpnLog.add("sslocal", line)
                }
            }
        } catch (_: Throwable) {
        }
    }
}
