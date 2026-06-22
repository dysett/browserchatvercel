package ua.finalproject.chat.protocol;

import java.util.Objects;

public record Packet(int source, long packetId, ChatMessage message) {
    public Packet {
        if (source < 0 || source > 255) {
            throw new IllegalArgumentException("source must fit into one byte");
        }
        if (packetId < 0) {
            throw new IllegalArgumentException("packetId must be zero or positive");
        }
        Objects.requireNonNull(message, "message");
    }
}
