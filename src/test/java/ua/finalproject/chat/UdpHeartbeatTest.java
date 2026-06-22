package ua.finalproject.chat;

import org.junit.jupiter.api.Test;
import ua.finalproject.chat.udp.UdpHeartbeatClient;
import ua.finalproject.chat.udp.UdpHeartbeatServer;

import java.net.InetSocketAddress;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UdpHeartbeatTest {
    @Test
    void retriesLostDatagramsUntilAcknowledgementArrives() throws Exception {
        try (UdpHeartbeatServer server = new UdpHeartbeatServer(0, 2)) {
            server.start();
            UdpHeartbeatClient client = new UdpHeartbeatClient(3, Duration.ofMillis(150));

            UdpHeartbeatClient.HeartbeatResult result = client.heartbeat(
                    new InetSocketAddress("127.0.0.1", server.port())
            );

            assertTrue(result.acknowledged());
            assertEquals(3, result.attempts());
        }
    }
}
