package ua.finalproject.chat.server;

import ua.finalproject.chat.protocol.ChatMessage;

public record OutboundEvent(String targetUsername, ChatMessage message) {
    public static OutboundEvent toUser(String username, ChatMessage message) {
        return new OutboundEvent(username, message);
    }

    public static OutboundEvent broadcast(ChatMessage message) {
        return new OutboundEvent(null, message);
    }

    public boolean broadcast() {
        return targetUsername == null;
    }
}
