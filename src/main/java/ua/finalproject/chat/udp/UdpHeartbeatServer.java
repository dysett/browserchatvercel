package ua.finalproject.chat.udp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class UdpHeartbeatServer implements AutoCloseable {
    private final int requestedPort;
    private final AtomicInteger acknowledgementsToDrop;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private volatile boolean running;
    private DatagramSocket socket;

    public UdpHeartbeatServer(int port) {
        this(port, 0);
    }

    public UdpHeartbeatServer(int port, int acknowledgementsToDrop) {
        if (acknowledgementsToDrop < 0) {
            throw new IllegalArgumentException("acknowledgementsToDrop cannot be negative");
        }
        this.requestedPort = port;
        this.acknowledgementsToDrop = new AtomicInteger(acknowledgementsToDrop);
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        try {
            socket = new DatagramSocket(requestedPort);
            running = true;
            executor.submit(this::receiveLoop);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start UDP heartbeat server", e);
        }
    }

    public int port() {
        if (socket == null) {
            throw new IllegalStateException("UDP heartbeat server is not started");
        }
        return socket.getLocalPort();
    }

    private void receiveLoop() {
        while (running) {
            try {
                byte[] buffer = new byte[256];
                DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                socket.receive(request);

                String payload = new String(request.getData(), request.getOffset(), request.getLength(), StandardCharsets.UTF_8);
                if (!payload.startsWith("PING:")) {
                    continue;
                }
                if (acknowledgementsToDrop.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                    continue;
                }

                byte[] acknowledgement = ("ACK:" + payload.substring("PING:".length())).getBytes(StandardCharsets.UTF_8);
                DatagramPacket response = new DatagramPacket(
                        acknowledgement,
                        acknowledgement.length,
                        request.getAddress(),
                        request.getPort()
                );
                socket.send(response);
            } catch (IOException e) {
                if (running) {
                    throw new IllegalStateException("UDP heartbeat server stopped unexpectedly", e);
                }
            }
        }
    }

    @Override
    public synchronized void close() {
        running = false;
        if (socket != null) {
            socket.close();
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
