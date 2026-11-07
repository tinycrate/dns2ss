import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tiny DNS responder for emulator/device tests.
 * Returns A 10.9.9.9 for every query and logs the query count.
 * Usage: java TestDnsServer.java [port]
 */
public class TestDnsServer {
    private static final AtomicLong udpCount = new AtomicLong();
    private static final AtomicLong tcpCount = new AtomicLong();

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 53530;
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        DatagramSocket udp = new DatagramSocket(port, loopback);
        ServerSocket tcp = new ServerSocket(port, 50, loopback);
        System.out.println("Test DNS listening on 127.0.0.1:" + port + " (UDP+TCP), answer 10.9.9.9");
        Thread udpThread = new Thread(() -> {
            byte[] buffer = new byte[4096];
            while (true) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    udp.receive(packet);
                    byte[] response = answer(packet.getData(), packet.getLength());
                    if (response != null) {
                        udp.send(new DatagramPacket(response, response.length, packet.getSocketAddress()));
                        long n = udpCount.incrementAndGet();
                        if (n % 10 == 0 || n < 5) System.out.println("UDP queries=" + n);
                    }
                } catch (Throwable t) {
                    System.out.println("UDP error: " + t);
                }
            }
        }, "testdns-udp");
        udpThread.setDaemon(true);
        udpThread.start();

        while (true) {
            Socket socket = tcp.accept();
            try (Socket s = socket) {
                DataInputStream in = new DataInputStream(s.getInputStream());
                DataOutputStream out = new DataOutputStream(s.getOutputStream());
                while (true) {
                    int length = in.readUnsignedShort();
                    if (length <= 0) break;
                    byte[] query = new byte[length];
                    in.readFully(query);
                    byte[] response = answer(query, query.length);
                    if (response == null) break;
                    out.writeShort(response.length);
                    out.write(response);
                    out.flush();
                    long n = tcpCount.incrementAndGet();
                    if (n % 10 == 0 || n < 5) System.out.println("TCP queries=" + n);
                }
            } catch (Throwable t) {
                System.out.println("TCP error: " + t);
            }
        }
    }

    private static byte[] answer(byte[] query, int length) {
        if (length < 12) return null;
        int offset = 12;
        while (offset < length && query[offset] != 0) {
            int label = query[offset] & 0xff;
            if (label > 63) return null;
            offset += 1 + label;
        }
        if (offset + 1 + 4 > length) return null;
        int questionEnd = offset + 1 + 4;
        int qtype = ((query[offset + 1] & 0xff) << 8) | (query[offset + 2] & 0xff);
        if (qtype != 1) {
            // Return NODATA for non-A queries.
            byte[] response = new byte[questionEnd];
            System.arraycopy(query, 0, response, 0, questionEnd);
            response[2] = (byte) 0x81;
            response[3] = (byte) 0x80;
            response[6] = 0; response[7] = 0;
            response[8] = 0; response[9] = 0;
            response[10] = 0; response[11] = 0;
            return response;
        }
        byte[] response = new byte[questionEnd + 16];
        System.arraycopy(query, 0, response, 0, questionEnd);
        response[2] = (byte) 0x81;
        response[3] = (byte) 0x80;
        response[6] = 0; response[7] = 1;
        response[8] = 0; response[9] = 0;
        response[10] = 0; response[11] = 0;
        int p = questionEnd;
        response[p++] = (byte) 0xC0;
        response[p++] = (byte) 0x0C;
        response[p++] = 0; response[p++] = 1;
        response[p++] = 0; response[p++] = 1;
        response[p++] = 0; response[p++] = 0; response[p++] = 0; response[p++] = 60;
        response[p++] = 0; response[p++] = 4;
        response[p++] = 10; response[p++] = 9; response[p++] = 9; response[p] = 9;
        return response;
    }
}
