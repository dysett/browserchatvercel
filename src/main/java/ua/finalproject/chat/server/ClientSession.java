package ua.finalproject.chat.server;

import ua.finalproject.chat.db.ChatUser;
import ua.finalproject.chat.protocol.ChatCommand;
import ua.finalproject.chat.protocol.ChatMessage;
import ua.finalproject.chat.protocol.Packet;
import ua.finalproject.chat.protocol.PacketCodec;
import ua.finalproject.chat.protocol.PacketIo;
import ua.finalproject.chat.protocol.ProtocolException;

import java.io.IOException;
import java.net.Socket;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public final class ClientSession implements Runnable {
    private final Socket socket;
    private final PacketCodec codec;
    private final ChatProcessor processor;
    private final ClientRegistry registry;
    private final PacketIo packetIo;
    private final AtomicLong serverPacketIds = new AtomicLong(1);

    private volatile ChatUser currentUser;

    public ClientSession(Socket socket, PacketCodec codec, ChatProcessor processor, ClientRegistry registry) throws IOException {
        this.socket = Objects.requireNonNull(socket, "socket");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.processor = Objects.requireNonNull(processor, "processor");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.packetIo = new PacketIo(socket.getInputStream(), socket.getOutputStream());
    }

    @Override
    public void run() {
        try {
            while (!socket.isClosed()) {
                byte[] packetBytes = packetIo.readPacketBytes();
                if (packetBytes == null) {
                    return;
                }
                handle(packetBytes);
            }
        } catch (IOException e) {
            // Client disconnected.
        } finally {
            registry.unregister(this);
            closeQuietly();
        }
    }

    public void send(ChatMessage message) {
        try {
            Packet packet = new Packet(1, serverPacketIds.getAndIncrement(), message);
            packetIo.writePacketBytes(codec.encode(packet));
        } catch (IOException | RuntimeException e) {
            closeQuietly();
        }
    }

    public void close() {
        closeQuietly();
    }

    private void handle(byte[] packetBytes) {
        long requestPacketId = 0;
        try {
            Packet packet = codec.decode(packetBytes);
            requestPacketId = packet.packetId();
            ServerResult result = processor.process(packet.message(), currentUser);
            if (result.authenticatedUser() != null) {
                currentUser = result.authenticatedUser();
                registry.register(currentUser.username(), this);
            }
            sendWithPacketId(result.response(), requestPacketId);
            for (OutboundEvent event : result.events()) {
                if (event.broadcast()) {
                    registry.broadcast(event.message());
                } else {
                    registry.sendTo(event.targetUsername(), event.message());
                }
            }
        } catch (ProtocolException e) {
            sendWithPacketId(ChatMessage.of(ChatCommand.RESPONSE, 0, Map.of(
                    "ok", "false",
                    "message", "Packet rejected: " + e.getMessage()
            )), requestPacketId);
        }
    }

    private void sendWithPacketId(ChatMessage message, long packetId) {
        try {
            Packet packet = new Packet(1, packetId, message);
            packetIo.writePacketBytes(codec.encode(packet));
        } catch (IOException | RuntimeException e) {
            closeQuietly();
        }
    }

    private void closeQuietly() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
