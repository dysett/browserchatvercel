package ua.finalproject.chat.udp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

public final class UdpHeartbeatClient {
    private final int maxAttempts;
    private final Duration timeout;

    public UdpHeartbeatClient(int maxAttempts, Duration timeout) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        this.maxAttempts = maxAttempts;
        this.timeout = timeout;
    }

    public HeartbeatResult heartbeat(InetSocketAddress target) throws IOException {
        String nonce = Long.toUnsignedString(ThreadLocalRandom.current().nextLong());
        byte[] ping = ("PING:" + nonce).getBytes(StandardCharsets.UTF_8);
        String expectedAcknowledgement = "ACK:" + nonce;

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, timeout.toMillis()));
            InetAddress address = target.getAddress() == null ? InetAddress.getByName(target.getHostString()) : target.getAddress();

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                socket.send(new DatagramPacket(ping, ping.length, address, target.getPort()));
                try {
                    byte[] buffer = new byte[256];
                    DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                    socket.receive(response);
                    String acknowledgement = new String(
                            response.getData(),
                            response.getOffset(),
                            response.getLength(),
                            StandardCharsets.UTF_8
                    );
                    if (expectedAcknowledgement.equals(acknowledgement)) {
                        return new HeartbeatResult(true, attempt);
                    }
                } catch (SocketTimeoutException ignored) {
                    // UDP does not guarantee delivery, so the next loop iteration retries the heartbeat.
                }
            }
        }
        return new HeartbeatResult(false, maxAttempts);
    }

    public record HeartbeatResult(boolean acknowledged, int attempts) {
    }
}
