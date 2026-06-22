package ua.finalproject.chat.server;

import ua.finalproject.chat.db.ChatUser;
import ua.finalproject.chat.protocol.ChatMessage;

import java.util.List;

public record ServerResult(ChatMessage response, ChatUser authenticatedUser, List<OutboundEvent> events) {
    public static ServerResult response(ChatMessage response) {
        return new ServerResult(response, null, List.of());
    }

    public ServerResult withAuthenticatedUser(ChatUser user) {
        return new ServerResult(response, user, events);
    }

    public ServerResult withEvents(List<OutboundEvent> newEvents) {
        return new ServerResult(response, authenticatedUser, newEvents);
    }
}
