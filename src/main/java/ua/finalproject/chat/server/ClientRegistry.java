package ua.finalproject.chat.server;

import ua.finalproject.chat.protocol.ChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ClientRegistry {
    private final Map<String, ClientSession> sessions = new ConcurrentHashMap<>();

    public void register(String username, ClientSession session) {
        sessions.put(username, session);
    }

    public void unregister(ClientSession session) {
        sessions.entrySet().removeIf(entry -> entry.getValue() == session);
    }

    public void sendTo(String username, ChatMessage message) {
        ClientSession session = sessions.get(username);
        if (session != null) {
            session.send(message);
        }
    }

    public void broadcast(ChatMessage message) {
        sessions.values().forEach(session -> session.send(message));
    }

    public boolean isOnline(String username) {
        return sessions.containsKey(username);
    }

    public List<String> onlineUsers() {
        return new ArrayList<>(sessions.keySet());
    }

    public void closeAll() {
        sessions.values().forEach(ClientSession::close);
        sessions.clear();
    }
}
