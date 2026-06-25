package ua.finalproject.chat.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import ua.finalproject.chat.server.OutboundEvent;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Центр Server-Sent Events для браузерного клієнта.
 * Через нього сервер відправляє в браузер події про нові повідомлення, редагування, реакції та оновлення груп.
 */
final class BrowserEventHub implements AutoCloseable {
    private static final long KEEP_ALIVE_MILLIS = 5_000;

    private final ObjectMapper mapper;
    private final Map<String, Set<BrowserConnection>> connections = new ConcurrentHashMap<>();

    BrowserEventHub(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    void open(HttpExchange exchange, String username, Runnable keepAlive, Runnable onDisconnect) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);

        BrowserConnection connection = new BrowserConnection(exchange.getResponseBody());
        connections.computeIfAbsent(username, ignored -> ConcurrentHashMap.newKeySet()).add(connection);
        try {
            connection.send("connected", Map.of("status", "connected"));
            while (!Thread.currentThread().isInterrupted()) {
                keepAlive.run();
                Thread.sleep(KEEP_ALIVE_MILLIS);
                connection.keepAlive();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // The browser closed the event stream.
        } finally {
            Set<BrowserConnection> userConnections = connections.get(username);
            if (userConnections != null) {
                userConnections.remove(connection);
                if (userConnections.isEmpty()) {
                    connections.remove(username, userConnections);
                }
            }
            connection.close();
            onDisconnect.run();
        }
    }

    boolean hasConnections(String username) {
        Set<BrowserConnection> userConnections = connections.get(username);
        return userConnections != null && !userConnections.isEmpty();
    }

    void closeUser(String username) {
        Set<BrowserConnection> userConnections = connections.remove(username);
        if (userConnections != null) {
            userConnections.forEach(BrowserConnection::close);
        }
    }

    void publish(List<OutboundEvent> events) {
        for (OutboundEvent event : events) {
            BrowserEvent payload = new BrowserEvent(event.message().command().name(), event.message().fields());
            if (event.broadcast()) {
                List<BrowserConnection> recipients = new ArrayList<>();
                connections.values().forEach(recipients::addAll);
                send(recipients, payload);
            } else {
                send(connections.getOrDefault(event.targetUsername(), Set.of()), payload);
            }
        }
    }

    private void send(Iterable<BrowserConnection> recipients, BrowserEvent payload) {
        for (BrowserConnection connection : recipients) {
            try {
                connection.send("chat", payload);
            } catch (IOException ignored) {
                connection.close();
            }
        }
    }

    @Override
    public void close() {
        connections.values().forEach(items -> items.forEach(BrowserConnection::close));
        connections.clear();
    }

    private final class BrowserConnection {
        private final OutputStream output;

        private BrowserConnection(OutputStream output) {
            this.output = output;
        }

        private synchronized void send(String event, Object payload) throws IOException {
            String body = mapper.writeValueAsString(payload);
            output.write(("event: " + event + "\n").getBytes(StandardCharsets.UTF_8));
            output.write(("data: " + body + "\n\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
        }

        private synchronized void keepAlive() throws IOException {
            output.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
            output.flush();
        }

        private synchronized void close() {
            try {
                output.close();
            } catch (IOException ignored) {
            }
        }
    }

    private record BrowserEvent(String command, Map<String, String> fields) {
    }
}
