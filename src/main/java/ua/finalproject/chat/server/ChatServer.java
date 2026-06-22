package ua.finalproject.chat.server;

import ua.finalproject.chat.db.ChatDatabase;
import ua.finalproject.chat.db.ChatUser;
import ua.finalproject.chat.protocol.ChatMessage;
import ua.finalproject.chat.protocol.AesMessageCipher;
import ua.finalproject.chat.protocol.PacketCodec;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class ChatServer implements AutoCloseable {
    private final int requestedPort;
    private final ChatDatabase database;
    private final PacketCodec codec;
    private final ClientRegistry registry = new ClientRegistry();
    private final ChatProcessor processor;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private volatile boolean running;
    private ServerSocket serverSocket;

    public ChatServer(int port, ChatDatabase database, String sharedSecret) {
        this.requestedPort = port;
        this.database = Objects.requireNonNull(database, "database");
        this.codec = new PacketCodec(new AesMessageCipher(sharedSecret));
        this.processor = new ChatProcessor(database, registry);
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        try {
            serverSocket = new ServerSocket(requestedPort);
            running = true;
            executor.submit(this::acceptLoop);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start chat server", e);
        }
    }

    public List<String> onlineUsers() {
        return registry.onlineUsers();
    }

    public int port() {
        if (serverSocket == null) {
            throw new IllegalStateException("Server is not started");
        }
        return serverSocket.getLocalPort();
    }

    public ServerResult processHttp(ChatMessage request, ChatUser currentUser) {
        ServerResult result = processor.process(request, currentUser);
        dispatchEvents(result.events());
        return result;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                executor.submit(new ClientSession(socket, codec, processor, registry));
            } catch (IOException e) {
                if (running) {
                    throw new IllegalStateException("Cannot accept client", e);
                }
            }
        }
    }

    private void dispatchEvents(List<OutboundEvent> events) {
        for (OutboundEvent event : events) {
            if (event.broadcast()) {
                registry.broadcast(event.message());
            } else {
                registry.sendTo(event.targetUsername(), event.message());
            }
        }
    }

    @Override
    public synchronized void close() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        registry.closeAll();
        executor.shutdownNow();
        try {
            executor.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
