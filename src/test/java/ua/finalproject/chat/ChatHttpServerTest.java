package ua.finalproject.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ua.finalproject.chat.db.ChatDatabase;
import ua.finalproject.chat.http.ChatHttpServer;
import ua.finalproject.chat.http.JwtTokenService;
import ua.finalproject.chat.server.ChatServer;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatHttpServerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void browserApiRegistersUsersProtectsDataAndExchangesMessages() throws Exception {
        try (ChatDatabase database = new ChatDatabase("jdbc:sqlite::memory:");
             ChatServer tcpServer = new ChatServer(0, database, "tcp-secret");
             ChatHttpServer httpServer = new ChatHttpServer(0, database, tcpServer, new JwtTokenService("jwt-secret"))) {
            tcpServer.start();
            httpServer.start();
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            String baseUrl = "http://localhost:" + httpServer.port();

            HttpResponse<String> page = call(client, baseUrl, "GET", "/", null, null);
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("Online Chat"));

            HttpResponse<String> denied = call(client, baseUrl, "GET", "/api/chats", null, null);
            assertEquals(401, denied.statusCode());

            String aliceToken = register(client, baseUrl, "alice", "pass");
            String bobToken = register(client, baseUrl, "bob", "pass");

            HttpResponse<String> noFriends = call(client, baseUrl, "GET", "/api/chats", aliceToken, null);
            assertFalse(noFriends.body().contains("\"type\":\"private\""));

            addFriend(client, baseUrl, aliceToken, "bob");
            HttpResponse<String> chats = call(client, baseUrl, "GET", "/api/chats", aliceToken, null);
            assertEquals(200, chats.statusCode());
            assertTrue(chats.body().contains("bob"));

            HttpResponse<String> sent = call(client, baseUrl, "POST", "/api/messages", aliceToken,
                    mapper.writeValueAsString(Map.of("to", "bob", "text", "Hello from browser")));
            assertEquals(202, sent.statusCode());

            HttpResponse<String> history = call(client, baseUrl, "GET", "/api/messages?with=alice", bobToken, null);
            assertEquals(200, history.statusCode());
            JsonNode messages = mapper.readTree(history.body()).path("messages");
            assertEquals(1, messages.size());
            assertEquals("alice", messages.get(0).path("sender").asText());
            assertEquals("Hello from browser", messages.get(0).path("text").asText());
            assertFalse(messages.get(0).path("deleted").asBoolean());
        }
    }

    @Test
    void browserApiExposesTypingAndAdministratorActions() throws Exception {
        try (ChatDatabase database = new ChatDatabase("jdbc:sqlite::memory:");
             ChatServer tcpServer = new ChatServer(0, database, "tcp-secret");
             ChatHttpServer httpServer = new ChatHttpServer(0, database, tcpServer, new JwtTokenService("jwt-secret"))) {
            tcpServer.start();
            httpServer.start();
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://localhost:" + httpServer.port();
            String adminToken = register(client, baseUrl, "admin", "pass");
            String bobToken = register(client, baseUrl, "bob", "pass");
            addFriend(client, baseUrl, adminToken, "bob");

            HttpResponse<String> typing = call(client, baseUrl, "POST", "/api/typing", adminToken,
                    mapper.writeValueAsString(Map.of("to", "bob")));
            assertEquals(202, typing.statusCode());
            HttpResponse<String> typingState = call(client, baseUrl, "GET", "/api/typing?with=admin", bobToken, null);
            assertEquals(200, typingState.statusCode());
            assertTrue(typingState.body().contains("admin"));

            HttpResponse<String> sent = call(client, baseUrl, "POST", "/api/messages", bobToken,
                    mapper.writeValueAsString(Map.of("to", "admin", "text", "message for moderation")));
            assertEquals(202, sent.statusCode());
            JsonNode history = mapper.readTree(call(client, baseUrl, "GET", "/api/messages?with=admin", bobToken, null).body());
            long messageId = history.path("messages").get(0).path("id").asLong();

            HttpResponse<String> deleted = call(client, baseUrl, "DELETE", "/api/admin/messages/" + messageId, adminToken, null);
            assertEquals(200, deleted.statusCode());
            JsonNode removed = mapper.readTree(call(client, baseUrl, "GET", "/api/messages?with=admin", bobToken, null).body());
            assertTrue(removed.path("messages").get(0).path("deleted").asBoolean());

            HttpResponse<String> blocked = call(client, baseUrl, "POST", "/api/admin/users/bob/block", adminToken, null);
            assertEquals(200, blocked.statusCode());
            HttpResponse<String> bobAfterBlock = call(client, baseUrl, "GET", "/api/chats", bobToken, null);
            assertEquals(401, bobAfterBlock.statusCode());
        }
    }

    @Test
    void eventStreamPushesNewMessageToRecipientBrowser() throws Exception {
        try (ChatDatabase database = new ChatDatabase("jdbc:sqlite::memory:");
             ChatServer tcpServer = new ChatServer(0, database, "tcp-secret");
             ChatHttpServer httpServer = new ChatHttpServer(0, database, tcpServer, new JwtTokenService("jwt-secret"))) {
            tcpServer.start();
            httpServer.start();
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://localhost:" + httpServer.port();
            String aliceToken = register(client, baseUrl, "alice", "pass");
            String bobToken = register(client, baseUrl, "bob", "pass");
            addFriend(client, baseUrl, aliceToken, "bob");

            HttpRequest eventRequest = HttpRequest.newBuilder(URI.create(baseUrl + "/api/events"))
                    .header("Authorization", "Bearer " + aliceToken)
                    .GET()
                    .build();
            HttpResponse<InputStream> stream = client.sendAsync(eventRequest, HttpResponse.BodyHandlers.ofInputStream())
                    .get(3, TimeUnit.SECONDS);
            assertEquals(200, stream.statusCode());
            assertTrue(stream.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream.body(), StandardCharsets.UTF_8))) {
                HttpResponse<String> sent = call(client, baseUrl, "POST", "/api/messages", bobToken,
                        mapper.writeValueAsString(Map.of("to", "alice", "text", "real-time message")));
                assertEquals(202, sent.statusCode());

                String event = awaitMessageEvent(reader);
                assertTrue(event.contains("EVENT_MESSAGE"));
                assertTrue(event.contains("real-time message"));
            }
        }
    }

    @Test
    void concurrentHttpRequestsPreserveEveryMessage() throws Exception {
        try (ChatDatabase database = new ChatDatabase("jdbc:sqlite::memory:");
             ChatServer tcpServer = new ChatServer(0, database, "tcp-secret");
             ChatHttpServer httpServer = new ChatHttpServer(0, database, tcpServer, new JwtTokenService("jwt-secret"))) {
            tcpServer.start();
            httpServer.start();
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://localhost:" + httpServer.port();
            String aliceToken = register(client, baseUrl, "alice", "pass");
            String bobToken = register(client, baseUrl, "bob", "pass");
            addFriend(client, baseUrl, aliceToken, "bob");

            int messageCount = 20;
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService workers = Executors.newFixedThreadPool(8);
            try {
                List<Future<Integer>> results = new ArrayList<>();
                for (int index = 0; index < messageCount; index++) {
                    int messageIndex = index;
                    results.add(workers.submit(() -> {
                        start.await();
                        HttpResponse<String> response = call(client, baseUrl, "POST", "/api/messages", aliceToken,
                                mapper.writeValueAsString(Map.of("to", "bob", "text", "parallel-" + messageIndex)));
                        return response.statusCode();
                    }));
                }
                start.countDown();
                for (Future<Integer> result : results) {
                    assertEquals(202, result.get(5, TimeUnit.SECONDS));
                }
            } finally {
                workers.shutdownNow();
                workers.awaitTermination(3, TimeUnit.SECONDS);
            }

            JsonNode messages = mapper.readTree(call(client, baseUrl, "GET", "/api/messages?with=alice", bobToken, null).body())
                    .path("messages");
            assertEquals(messageCount, messages.size());
            Set<String> texts = new HashSet<>();
            messages.forEach(message -> texts.add(message.path("text").asText()));
            assertEquals(messageCount, texts.size());
        }
    }

    @Test
    void groupJoinRequiresExistingGroupAndCreatorControlsItsLifecycle() throws Exception {
        try (ChatDatabase database = new ChatDatabase("jdbc:sqlite::memory:");
             ChatServer tcpServer = new ChatServer(0, database, "tcp-secret");
             ChatHttpServer httpServer = new ChatHttpServer(0, database, tcpServer, new JwtTokenService("jwt-secret"))) {
            tcpServer.start();
            httpServer.start();
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://localhost:" + httpServer.port();
            String aliceToken = register(client, baseUrl, "alice", "pass");
            String bobToken = register(client, baseUrl, "bob", "pass");

            HttpResponse<String> missingGroup = call(client, baseUrl, "POST", "/api/groups", aliceToken,
                    mapper.writeValueAsString(Map.of("action", "join", "group", "missing")));
            assertEquals(400, missingGroup.statusCode());
            assertTrue(missingGroup.body().contains("Group not found"));

            HttpResponse<String> created = call(client, baseUrl, "POST", "/api/groups", aliceToken,
                    mapper.writeValueAsString(Map.of("action", "create", "group", "team")));
            assertEquals(200, created.statusCode());

            HttpResponse<String> members = call(client, baseUrl, "GET", "/api/groups/team/members", aliceToken, null);
            JsonNode memberList = mapper.readTree(members.body());
            assertTrue(memberList.path("owner").asBoolean());
            assertEquals(List.of("alice"), mapper.convertValue(memberList.path("members"), mapper.getTypeFactory().constructCollectionType(List.class, String.class)));

            HttpResponse<String> added = call(client, baseUrl, "POST", "/api/groups/team/members", aliceToken,
                    mapper.writeValueAsString(Map.of("username", "bob")));
            assertEquals(200, added.statusCode());

            HttpResponse<String> removed = call(client, baseUrl, "DELETE", "/api/groups/team/members", aliceToken,
                    mapper.writeValueAsString(Map.of("username", "bob")));
            assertEquals(200, removed.statusCode());

            assertEquals(200, call(client, baseUrl, "POST", "/api/groups/team/members", aliceToken,
                    mapper.writeValueAsString(Map.of("username", "bob"))).statusCode());
            HttpResponse<String> left = call(client, baseUrl, "DELETE", "/api/groups/team/membership", bobToken, null);
            assertEquals(200, left.statusCode());

            HttpResponse<String> deleted = call(client, baseUrl, "DELETE", "/api/groups/team", aliceToken, null);
            assertEquals(200, deleted.statusCode());
        }
    }

    @Test
    void serverAcceptsConfiguredVercelOriginForCorsRequests() throws Exception {
        try (ChatDatabase database = new ChatDatabase("jdbc:sqlite::memory:");
             ChatServer tcpServer = new ChatServer(0, database, "tcp-secret");
             ChatHttpServer httpServer = new ChatHttpServer(
                     0,
                     database,
                     tcpServer,
                     new JwtTokenService("jwt-secret"),
                     "https://browserchatvercel.vercel.app"
             )) {
            tcpServer.start();
            httpServer.start();
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + httpServer.port() + "/api/chats"))
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                    .header("Origin", "https://browserchatvercel.vercel.app")
                    .header("Access-Control-Request-Method", "GET")
                    .header("Access-Control-Request-Headers", "Authorization")
                    .build();

            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(204, response.statusCode());
            assertEquals("https://browserchatvercel.vercel.app",
                    response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
            assertTrue(response.headers().firstValue("Access-Control-Allow-Headers").orElseThrow().contains("Authorization"));
        }
    }

    @Test
    void browserApiSearchesUsersWithoutExposingTheFullDirectoryToRegularUsers() throws Exception {
        try (ChatDatabase database = new ChatDatabase("jdbc:sqlite::memory:");
             ChatServer tcpServer = new ChatServer(0, database, "tcp-secret");
             ChatHttpServer httpServer = new ChatHttpServer(0, database, tcpServer, new JwtTokenService("jwt-secret"))) {
            tcpServer.start();
            httpServer.start();
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://localhost:" + httpServer.port();
            String adminToken = register(client, baseUrl, "admin", "pass");
            String aliceToken = register(client, baseUrl, "alice", "pass");
            String bobToken = register(client, baseUrl, "bob", "pass");

            JsonNode aliceChats = mapper.readTree(call(client, baseUrl, "GET", "/api/chats", aliceToken, null).body());
            assertEquals(0, aliceChats.path("users").size());

            JsonNode search = mapper.readTree(call(client, baseUrl, "GET", "/api/users/search?query=bo", aliceToken, null).body());
            assertEquals(1, search.path("users").size());
            assertEquals("bob", search.path("users").get(0).path("username").asText());

            JsonNode adminChats = mapper.readTree(call(client, baseUrl, "GET", "/api/chats", adminToken, null).body());
            assertEquals(3, adminChats.path("users").size());

            HttpResponse<String> deleted = call(client, baseUrl, "DELETE", "/api/admin/users/bob", adminToken, null);
            assertEquals(200, deleted.statusCode());
            assertEquals(401, call(client, baseUrl, "GET", "/api/chats", bobToken, null).statusCode());
            assertEquals(0, mapper.readTree(call(client, baseUrl, "GET", "/api/users/search?query=bo", aliceToken, null).body())
                    .path("users").size());
        }
    }

    @Test
    void deletingPrivateChatRemovesHistoryAndFriendship() throws Exception {
        try (ChatDatabase database = new ChatDatabase("jdbc:sqlite::memory:");
             ChatServer tcpServer = new ChatServer(0, database, "tcp-secret");
             ChatHttpServer httpServer = new ChatHttpServer(0, database, tcpServer, new JwtTokenService("jwt-secret"))) {
            tcpServer.start();
            httpServer.start();
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://localhost:" + httpServer.port();
            String aliceToken = register(client, baseUrl, "alice", "pass");
            String bobToken = register(client, baseUrl, "bob", "pass");
            addFriend(client, baseUrl, aliceToken, "bob");

            assertEquals(202, call(client, baseUrl, "POST", "/api/messages", aliceToken,
                    mapper.writeValueAsString(Map.of("to", "bob", "text", "erase me"))).statusCode());
            assertEquals(200, call(client, baseUrl, "DELETE", "/api/friends/bob", aliceToken, null).statusCode());

            addFriend(client, baseUrl, aliceToken, "bob");
            JsonNode history = mapper.readTree(call(client, baseUrl, "GET", "/api/messages?with=alice", bobToken, null).body());
            assertEquals(0, history.path("messages").size());
        }
    }

    private static String awaitMessageEvent(BufferedReader reader) throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            String payload = readSseEvent(reader);
            if (payload.contains("EVENT_MESSAGE")) {
                return payload;
            }
        }
        throw new IllegalStateException("No message event arrived");
    }

    private static String readSseEvent(BufferedReader reader) throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<String> event = worker.submit(() -> {
                StringBuilder data = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() && !data.isEmpty()) {
                        return data.toString();
                    }
                    if (line.startsWith("data: ")) {
                        data.append(line.substring(6));
                    }
                }
                throw new IllegalStateException("SSE stream closed before a message event arrived");
            });
            return event.get(3, TimeUnit.SECONDS);
        } finally {
            worker.shutdownNow();
        }
    }

    private String register(HttpClient client, String baseUrl, String username, String password) throws Exception {
        HttpResponse<String> response = call(client, baseUrl, "POST", "/api/register", null,
                mapper.writeValueAsString(Map.of("username", username, "password", password)));
        assertEquals(201, response.statusCode());
        return mapper.readTree(response.body()).path("token").asText();
    }

    private static void addFriend(HttpClient client, String baseUrl, String token, String username) throws Exception {
        HttpResponse<String> response = call(client, baseUrl, "POST", "/api/friends/" + username, token, null);
        assertEquals(200, response.statusCode());
    }

    private static HttpResponse<String> call(
            HttpClient client,
            String baseUrl,
            String method,
            String path,
            String token,
            String body
    ) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(3));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
