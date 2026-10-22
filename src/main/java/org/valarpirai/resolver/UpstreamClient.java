package org.valarpirai.resolver;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.valarpirai.protocol.*;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Sends one query to one upstream nameserver over UDP, retrying over TCP when truncated.
 */
@Slf4j
@RequiredArgsConstructor
class UpstreamClient {
    private static final int BUFFER_SIZE = 4096;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final int timeoutMs;
    private final boolean debug;

    /**
     * Query a specific nameserver
     */
    DnsResponse query(String qname, int qtype, String nameserver) throws IOException {
        int id = RANDOM.nextInt(65536);
        DnsRequest request = DnsRequest.builder()
                .header(DnsHeader.builder().id(id).qr(false).rd(false).qdcount(1).build())
                .build();
        request.addQuestion(DnsQuestion.builder().name(qname).type(qtype).qclass(1).build());
        byte[] queryBytes = DnsWire.buildQuery(request);

        InetSocketAddress serverSocket = new InetSocketAddress(InetAddress.getByName(nameserver), 53);
        long queryStartTime = System.currentTimeMillis();

        // DatagramSocket honors setSoTimeout; DatagramChannel.receive does not
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(timeoutMs);
            socket.send(new DatagramPacket(queryBytes, queryBytes.length, serverSocket));

            byte[] buf = new byte[BUFFER_SIZE];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            long deadline = queryStartTime + timeoutMs;

            // Ignore packets from other sources or with a wrong ID until the deadline
            while (true) {
                int remaining = (int) (deadline - System.currentTimeMillis());
                if (remaining <= 0) {
                    throw new SocketTimeoutException("No valid response from " + nameserver);
                }
                socket.setSoTimeout(remaining);
                socket.receive(packet);

                boolean fromServer = serverSocket.getAddress().equals(packet.getAddress())
                        && packet.getPort() == 53;
                boolean idMatches = packet.getLength() >= 2
                        && (((buf[0] & 0xFF) << 8) | (buf[1] & 0xFF)) == id;
                if (fromServer && idMatches) {
                    break;
                }
            }

            if (debug) {
                log.info(String.format("Query to %s completed in %d ms",
                        nameserver, System.currentTimeMillis() - queryStartTime));
            }
            DnsResponse response = DnsWire.parseFullResponse(Arrays.copyOf(buf, packet.getLength()), request);
            if (response != null && response.getHeader().tc()) {
                return queryTcp(queryBytes, serverSocket, request);
            }
            return response;
        }
    }

    /**
     * Retry a truncated UDP query over TCP (2-byte length prefix per RFC 1035)
     */
    private DnsResponse queryTcp(byte[] queryBytes, InetSocketAddress server, DnsRequest request) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(server, timeoutMs);
            socket.setSoTimeout(timeoutMs);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeShort(queryBytes.length);
            out.write(queryBytes);
            out.flush();

            DataInputStream in = new DataInputStream(socket.getInputStream());
            byte[] responseBytes = new byte[in.readUnsignedShort()];
            in.readFully(responseBytes);
            return DnsWire.parseFullResponse(responseBytes, request);
        }
    }
}
