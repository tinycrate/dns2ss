package com.tiny.dns2ss.socks

import com.tiny.dns2ss.config.VpnMode
import com.tiny.dns2ss.dns.DnsCache
import com.tiny.dns2ss.dns.DnsTunnel
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class RouterConfig(
    val mode: VpnMode,
    val tunnelHost: String,
    val tunnelPort: Int,
    val socksUpstreamHost: String? = null,
    val socksUpstreamPort: Int? = null,
    val username: String? = null,
    val password: String? = null,
    val logger: (String) -> Unit = {},
)

class RouterStats {
    val dnsQueries = AtomicLong()
    val dnsResponses = AtomicLong()
    val directBytes = AtomicLong()
    val tunnelBytes = AtomicLong()
    val fullBytes = AtomicLong()
    val errors = AtomicLong()
    val cacheHits = AtomicLong()
    val cacheMisses = AtomicLong()
}

/**
 * Local SOCKS5 server used by hev.
 *
 * DNS (destination port 53) always goes to the sslocal tunnel. Other destinations are:
 *  - Strict: direct sockets
 *  - Full: through the local sslocal SOCKS server, so all traffic goes through SS
 */
class Socks5Router(private val config: RouterConfig) {
    private val running = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "socks5-router").apply { isDaemon = true }
    }
    private val active = Collections.synchronizedSet(mutableSetOf<Closeable>())
    private val associations = Collections.synchronizedSet(mutableSetOf<UdpAssociation>())
    private var serverSocket: ServerSocket? = null
    private var dnsTunnel: DnsTunnel? = null

    @Volatile
    private var listenPort: Int = 0

    val stats = RouterStats()
    val dnsCache = DnsCache()

    fun cacheStats(): DnsCache.Stats = dnsCache.stats()

    fun pendingDnsCount(): Int = dnsTunnel?.pendingCount() ?: 0

    fun start(): Int {
        check(!running.get()) { "Router already started" }
        val server = ServerSocket()
        try {
            server.reuseAddress = true
            server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
            dnsTunnel = DnsTunnel(config.tunnelHost, config.tunnelPort, dnsCache, stats, config.logger)
        } catch (t: Throwable) {
            runCatching { server.close() }
            throw t
        }
        serverSocket = server
        listenPort = server.localPort
        running.set(true)
        executor.execute { acceptLoop(server) }
        config.logger("Router listening on 127.0.0.1:$listenPort")
        return listenPort
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { dnsTunnel?.close() }
        dnsTunnel = null
        dnsCache.clear()
        synchronized(active) {
            for (closeable in active.toList()) runCatching { closeable.close() }
            active.clear()
        }
        synchronized(associations) {
            for (association in associations.toList()) association.close()
            associations.clear()
        }
        executor.shutdownNow()
        runCatching { executor.awaitTermination(2, TimeUnit.SECONDS) }
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running.get()) {
            try {
                val client = server.accept()
                client.tcpNoDelay = true
                track(client)
                executor.execute { handleClient(client) }
            } catch (_: SocketException) {
                if (!running.get()) return
            } catch (t: Throwable) {
                stats.errors.incrementAndGet()
                config.logger("accept error: ${t.message}")
            }
        }
    }

    private fun handleClient(client: Socket) {
        var remote: Socket? = null
        try {
            client.soTimeout = 15_000
            val input = DataInputStream(client.getInputStream())
            val output = DataOutputStream(client.getOutputStream())

            if (!Socks5Protocol.greet(input, output, config.username, config.password)) {
                config.logger("SOCKS auth rejected")
                return
            }

            val (command, target) = Socks5Protocol.request(input)
            client.soTimeout = 0

            when (command) {
                Socks5Protocol.CMD_CONNECT -> {
                    remote = openTcp(target)
                    Socks5Protocol.writeReply(output, Socks5Protocol.REP_SUCCESS)
                    val counter = when {
                        target.port == DNS_PORT -> stats.tunnelBytes
                        config.mode == VpnMode.FULL -> stats.fullBytes
                        else -> stats.directBytes
                    }
                    relay(client, remote, input, output, counter)
                }

                Socks5Protocol.CMD_UDP_ASSOCIATE -> {
                    val udpRelay = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
                    track(udpRelay)
                    val association = UdpAssociation(client, input, udpRelay)
                    associations.add(association)
                    Socks5Protocol.writeReply(
                        output,
                        Socks5Protocol.REP_SUCCESS,
                        Socks5Address.fromInetAddress(udpRelay.localAddress, udpRelay.localPort),
                    )
                    association.start()
                    association.awaitControl()
                    associations.remove(association)
                    association.close()
                }

                else -> Socks5Protocol.writeReply(output, Socks5Protocol.REP_COMMAND_NOT_SUPPORTED)
            }
        } catch (t: Throwable) {
            stats.errors.incrementAndGet()
            if (t is IOException || t is IllegalArgumentException) {
                config.logger("client error: ${t.message}")
            } else {
                config.logger("client error: ${t.javaClass.simpleName}: ${t.message}")
            }
        } finally {
            runCatching { remote?.close() }
            untrack(client)
            runCatching { client.close() }
        }
    }

    private fun openTcp(target: Socks5Address): Socket {
        val socket = when {
            target.port == DNS_PORT -> {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(config.tunnelHost, config.tunnelPort), CONNECT_TIMEOUT_MS)
                s
            }
            config.mode == VpnMode.FULL -> {
                val host = config.socksUpstreamHost
                val port = config.socksUpstreamPort
                require(host != null && port != null) { "Full mode requires a SOCKS upstream" }
                Socks5TcpUpstream(host, port, CONNECT_TIMEOUT_MS).connect(target)
            }
            else -> {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(target.socketAddress(), CONNECT_TIMEOUT_MS)
                s
            }
        }
        track(socket)
        return socket
    }

    private fun relay(
        client: Socket,
        remote: Socket,
        clientInput: InputStream,
        clientOutput: OutputStream,
        counter: AtomicLong,
    ) {
        val done = CountDownLatch(2)
        executor.execute {
            copy(clientInput, remote.getOutputStream(), remote, client, done, counter)
        }
        executor.execute {
            copy(remote.getInputStream(), clientOutput, client, remote, done, counter)
        }
        done.await()
        untrack(remote)
        untrack(client)
        runCatching { remote.close() }
        runCatching { client.close() }
    }

    private fun copy(
        source: InputStream,
        destination: OutputStream,
        destinationSocket: Socket,
        sourceSocket: Socket,
        done: CountDownLatch,
        counter: AtomicLong,
    ) {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        try {
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                counter.addAndGet(read.toLong())
                destination.write(buffer, 0, read)
                destination.flush()
            }
            runCatching { destinationSocket.shutdownOutput() }
        } catch (t: Throwable) {
            stats.errors.incrementAndGet()
            runCatching { sourceSocket.close() }
            runCatching { destinationSocket.close() }
        } finally {
            done.countDown()
        }
    }

    private inner class UdpAssociation(
        private val control: Socket,
        private val controlInput: DataInputStream,
        private val relay: DatagramSocket,
    ) {
        private val closed = AtomicBoolean(false)
        @Volatile
        private var clientAddress: InetSocketAddress? = null
        private var directSocket: DatagramSocket? = null
        private var directThreadStarted = false
        private var socksUdp: Socks5UdpClient? = null
        private var socksThreadStarted = false
        private var receiverThread: Thread? = null

        fun start() {
            receiverThread = Thread({ receiveLoop() }, "socks5-udp-recv").apply {
                isDaemon = true
                start()
            }
        }

        fun awaitControl() {
            try {
                while (!closed.get()) {
                    if (controlInput.read() < 0) break
                }
            } catch (_: IOException) {
            }
            close()
        }

        private fun receiveLoop() {
            val buffer = ByteArray(UDP_BUFFER_SIZE)
            while (!closed.get()) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    relay.receive(packet)
                    val parsed = Socks5Address.parseUdp(packet.data, packet.length) ?: continue
                    val client = packet.socketAddress as? InetSocketAddress ?: continue
                    clientAddress = client
                    val (target, payload) = parsed
                    if (target.port == DNS_PORT) {
                        routeDns(client, target, payload)
                    } else if (config.mode == VpnMode.FULL) {
                        routeFull(target, payload)
                    } else {
                        routeDirect(target, payload)
                    }
                } catch (t: Throwable) {
                    if (!closed.get()) {
                        stats.errors.incrementAndGet()
                        config.logger("udp recv error: ${t.message}")
                    }
                    if (t is SocketException && closed.get()) break
                }
            }
            close()
        }

        private fun routeDns(client: InetSocketAddress, target: Socks5Address, payload: ByteArray) {
            dnsTunnel?.query(payload, client, target) { response, source, responseClient ->
                sendToClient(responseClient, source, response, response.size)
            } ?: run {
                stats.errors.incrementAndGet()
                config.logger("DNS tunnel is not available")
            }
        }

        private fun routeDirect(target: Socks5Address, payload: ByteArray) {
            // Direct UDP must use a wildcard bind, not 127.0.0.1; a loopback-bound
            // socket cannot source a packet to the outside network.
            val socket = directSocket ?: DatagramSocket(0, InetAddress.getByName("0.0.0.0")).also {
                directSocket = it
                track(it)
            }
            if (!directThreadStarted) {
                directThreadStarted = true
                executor.execute { directReceiveLoop(socket) }
            }
            try {
                socket.send(DatagramPacket(payload, payload.size, target.socketAddress()))
                stats.directBytes.addAndGet(payload.size.toLong())
            } catch (t: Throwable) {
                stats.errors.incrementAndGet()
                config.logger("direct udp send failed: ${t.message}")
            }
        }

        private fun directReceiveLoop(socket: DatagramSocket) {
            val buffer = ByteArray(UDP_BUFFER_SIZE)
            while (!closed.get()) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val client = clientAddress ?: continue
                    val source = Socks5Address.fromInetAddress(packet.address, packet.port)
                    sendToClient(client, source, packet.data, packet.length)
                    stats.directBytes.addAndGet(packet.length.toLong())
                } catch (t: Throwable) {
                    if (!closed.get()) {
                        stats.errors.incrementAndGet()
                        config.logger("direct udp recv failed: ${t.message}")
                    }
                    if (closed.get()) break
                }
            }
        }

        private fun routeFull(target: Socks5Address, payload: ByteArray) {
            val client = socksUdp ?: Socks5UdpClient(
                config.socksUpstreamHost ?: error("Full mode has no SOCKS upstream host"),
                config.socksUpstreamPort ?: error("Full mode has no SOCKS upstream port"),
                CONNECT_TIMEOUT_MS,
            ).also {
                it.open()
                socksUdp = it
            }
            if (!socksThreadStarted) {
                socksThreadStarted = true
                executor.execute { fullReceiveLoop(client) }
            }
            client.send(target, payload)
            stats.fullBytes.addAndGet(payload.size.toLong())
        }

        private fun fullReceiveLoop(client: Socks5UdpClient) {
            while (!closed.get()) {
                try {
                    val (source, payload) = client.receive() ?: continue
                    val target = clientAddress ?: continue
                    sendToClient(target, source, payload, payload.size)
                    stats.fullBytes.addAndGet(payload.size.toLong())
                } catch (t: Throwable) {
                    if (!closed.get()) {
                        stats.errors.incrementAndGet()
                        config.logger("full udp recv failed: ${t.message}")
                    }
                    if (closed.get()) break
                }
            }
        }

        private fun sendToClient(
            client: InetSocketAddress,
            source: Socks5Address,
            payload: ByteArray,
            length: Int,
        ) {
            try {
                val response = Socks5Address.buildUdp(source, if (length == payload.size) payload else payload.copyOf(length))
                relay.send(DatagramPacket(response, response.size, client))
            } catch (t: Throwable) {
                stats.errors.incrementAndGet()
                config.logger("udp response send failed: ${t.message}")
            }
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { relay.close() }
            runCatching { control.close() }
            untrack(relay)
            runCatching { directSocket?.close() }
            runCatching { socksUdp?.close() }
            directSocket?.let { untrack(it) }
            receiverThread?.interrupt()
        }
    }

    private fun <T : Closeable> track(closeable: T): T {
        if (!running.get()) {
            runCatching { closeable.close() }
            throw SocketException("Router is stopped")
        }
        active.add(closeable)
        return closeable
    }

    private fun untrack(closeable: Closeable) {
        active.remove(closeable)
    }

    companion object {
        private const val DNS_PORT = 53
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val COPY_BUFFER_SIZE = 64 * 1024
        private const val UDP_BUFFER_SIZE = 65535
    }
}
