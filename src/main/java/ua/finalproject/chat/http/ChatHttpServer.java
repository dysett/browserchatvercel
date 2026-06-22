package ua.finalproject.chat.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ua.finalproject.chat.db.ChatDatabase;
import ua.finalproject.chat.db.ChatUser;
import ua.finalproject.chat.db.StoredMessage;
import ua.finalproject.chat.protocol.ChatCommand;
import ua.finalproject.chat.protocol.ChatMessage;
import ua.finalproject.chat.server.ChatServer;
import ua.finalproject.chat.server.OutboundEvent;
import ua.finalproject.chat.server.ServerResult;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public final class ChatHttpServer implements AutoCloseable {
    private static final String JSON = "application/json; charset=utf-8";
    private static final long TYPING_TTL_MILLIS = 2_500;
    private static final long BROWSER_PRESENCE_TTL_MILLIS = 8_000;

    private final ChatDatabase database;
    private final ChatServer chatServer;
    private final JwtTokenService tokenService;
    private final HttpServer server;
    private final ExecutorService executor;
    private final BrowserEventHub eventHub;
    private final Set<String> allowedOrigins;
    private final Map<String, Map<String, Long>> browserTyping = new ConcurrentHashMap<>();
    private final Map<String, Long> browserLastSeen = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public ChatHttpServer(int port, ChatDatabase database, ChatServer chatServer, JwtTokenService tokenService) throws IOException {
        this(port, database, chatServer, tokenService, "");
    }

    public ChatHttpServer(
            int port,
            ChatDatabase database,
            ChatServer chatServer,
            JwtTokenService tokenService,
            String allowedOrigin
    ) throws IOException {
        this.database = Objects.requireNonNull(database, "database");
        this.chatServer = Objects.requireNonNull(chatServer, "chatServer");
        this.tokenService = Objects.requireNonNull(tokenService, "tokenService");
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.executor = Executors.newCachedThreadPool();
        this.eventHub = new BrowserEventHub(mapper);
        this.allowedOrigins = Arrays.stream(Objects.requireNonNullElse(allowedOrigin, "").split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        this.server.setExecutor(executor);
        createContexts(this.tokenService);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        eventHub.close();
        server.stop(0);
        executor.shutdownNow();
    }

    private void createContexts(JwtTokenService tokenService) {
        server.createContext("/api/login", this::handleLogin);
        server.createContext("/api/register", this::handleRegister);
        HttpContext api = server.createContext("/api", this::handleProtectedApi);
        api.setAuthenticator(new BearerTokenAuthenticator(tokenService));
        server.createContext("/", this::handleStatic);
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        if (handleCorsPreflight(exchange)) {
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "POST");
            return;
        }
        try {
            Credentials request = readJson(exchange, Credentials.class);
            ChatUser user = database.authenticate(requireText(request.username(), "username"), requireText(request.password(), "password"));
            sendJson(exchange, 200, authResponse(user));
        } catch (IllegalArgumentException e) {
            sendError(exchange, 401, "Invalid username or password");
        } catch (RuntimeException e) {
            e.printStackTrace();
            sendError(exchange, 500, "Cannot process login request");
        }
    }

    private void handleRegister(HttpExchange exchange) throws IOException {
        if (handleCorsPreflight(exchange)) {
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "POST");
            return;
        }
        try {
            Credentials request = readJson(exchange, Credentials.class);
            ChatUser user = database.register(requireText(request.username(), "username"), requireText(request.password(), "password"));
            sendJson(exchange, 201, authResponse(user));
        } catch (IllegalArgumentException e) {
            sendError(exchange, 409, e.getMessage());
        } catch (RuntimeException e) {
            e.printStackTrace();
            sendError(exchange, 500, "Cannot process registration request");
        }
    }

    private AuthResponse authResponse(ChatUser user) {
        return new AuthResponse(tokenService.create(user.username()), "Bearer", user.username(), user.role().name());
    }

    private void handleProtectedApi(HttpExchange exchange) throws IOException {
        if (handleCorsPreflight(exchange)) {
            return;
        }
        try {
            ChatUser user = currentUser(exchange);
            markBrowserOnline(user.username());
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            if ("/api/me".equals(path) && "GET".equals(method)) {
                sendJson(exchange, 200, userDto(user));
                return;
            }
            if ("/api/chats".equals(path) && "GET".equals(method)) {
                chats(exchange, user);
                return;
            }
            if ("/api/events".equals(path)) {
                if (!"GET".equals(method)) {
                    methodNotAllowed(exchange, "GET");
                    return;
                }
                eventHub.open(exchange, user.username(), () -> markBrowserOnline(user.username()));
                return;
            }
            if ("/api/typing".equals(path)) {
                if ("GET".equals(method)) {
                    typingUsers(exchange, user);
                } else if ("POST".equals(method)) {
                    typing(exchange, user);
                } else {
                    methodNotAllowed(exchange, "GET, POST");
                }
                return;
            }
            if ("/api/messages".equals(path)) {
                if ("GET".equals(method)) {
                    history(exchange, user);
                } else if ("POST".equals(method)) {
                    sendMessage(exchange, user);
                } else {
                    methodNotAllowed(exchange, "GET, POST");
                }
                return;
            }
            if (path.startsWith("/api/messages/")) {
                messageById(exchange, user, path, method);
                return;
            }
            if ("/api/groups".equals(path)) {
                if ("POST".equals(method)) {
                    groupAction(exchange, user);
                } else {
                    methodNotAllowed(exchange, "POST");
                }
                return;
            }
            if (path.startsWith("/api/groups/") && path.endsWith("/members")) {
                groupMembers(exchange, user, path, method);
                return;
            }
            if (path.startsWith("/api/groups/") && path.endsWith("/membership")) {
                leaveGroup(exchange, user, path, method);
                return;
            }
            if (path.startsWith("/api/groups/")) {
                deleteGroup(exchange, user, path, method);
                return;
            }
            if (path.startsWith("/api/friends/")) {
                friendAction(exchange, user, path, method);
                return;
            }
            if (path.startsWith("/api/admin/users/")) {
                adminUser(exchange, user, path, method);
                return;
            }
            if (path.startsWith("/api/admin/messages/")) {
                adminMessage(exchange, user, path, method);
                return;
            }
            sendError(exchange, 404, "Endpoint not found");
        } catch (ApiException e) {
            sendError(exchange, e.status(), e.getMessage());
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, e.getMessage());
        } catch (RuntimeException e) {
            sendError(exchange, 500, "Internal server error");
        }
    }

    private void chats(HttpExchange exchange, ChatUser user) throws IOException {
        List<ChatUser> detailedUsers = database.listUsersDetailed();
        List<UserDto> users = detailedUsers.stream()
                .map(this::userDto)
                .toList();
        List<ChatUser> friends = database.friendsForUser(user.id());
        List<String> friendNames = friends.stream().map(ChatUser::username).toList();
        List<String> groups = database.groupsForUser(user.id());
        List<ChatSummaryDto> summaries = new ArrayList<>();
        for (ChatUser peer : friends) {
            if (!peer.blocked()) {
                String chat = database.privateChatNameWith(user.id(), peer.username());
                summaries.add(chatSummary("private", peer.username(), peer.username(), registryOnline(peer.username()), chat, user.id(), false));
            }
        }
        for (String group : groups) {
            summaries.add(chatSummary("group", group, group, false, group, user.id(), database.isGroupOwner(group, user.id())));
        }
        sendJson(exchange, 200, new ChatsResponse(userDto(user), users, friendNames, groups, summaries));
    }

    private void history(HttpExchange exchange, ChatUser user) throws IOException {
        String chat = resolveChat(query(exchange), user);
        database.markChatRead(chat, user.id());
        List<MessageDto> messages = database.history(chat, 100).stream().map(this::messageDto).toList();
        sendJson(exchange, 200, new MessagesResponse(messages));
    }

    private void typing(HttpExchange exchange, ChatUser user) throws IOException {
        TypingRequest request = readJson(exchange, TypingRequest.class);
        String target = request.to();
        String group = request.group();
        if (target != null && !target.isBlank() && (group == null || group.isBlank())) {
            markTyping(database.privateChatNameWith(user.id(), target.trim()), user.username());
            process(user, ChatMessage.of(ChatCommand.TYPING, user.id(), ChatMessage.fields("to", target.trim())));
        } else if (group != null && !group.isBlank() && (target == null || target.isBlank())) {
            markTyping(group.trim(), user.username());
            process(user, ChatMessage.of(ChatCommand.TYPING, user.id(), ChatMessage.fields("group", group.trim())));
        } else {
            throw new ApiException(400, "Specify exactly one typing target");
        }
        sendJson(exchange, 202, new ActionResponse("Typing updated"));
    }

    private void typingUsers(HttpExchange exchange, ChatUser user) throws IOException {
        String chat = resolveChat(query(exchange), user);
        sendJson(exchange, 200, new TypingResponse(activeTypers(chat, user.username())));
    }

    private void sendMessage(HttpExchange exchange, ChatUser user) throws IOException {
        MessageRequest request = readJson(exchange, MessageRequest.class);
        String text = requireText(request.text(), "text");
        ChatMessage message;
        if (request.to() != null && !request.to().isBlank() && (request.group() == null || request.group().isBlank())) {
            message = ChatMessage.of(ChatCommand.SEND_PRIVATE, user.id(), ChatMessage.fields("to", request.to().trim(), "text", text));
        } else if (request.group() != null && !request.group().isBlank() && (request.to() == null || request.to().isBlank())) {
            message = ChatMessage.of(ChatCommand.SEND_GROUP, user.id(), ChatMessage.fields("group", request.group().trim(), "text", text));
        } else {
            throw new ApiException(400, "Specify exactly one message target");
        }
        sendJson(exchange, 202, new ActionResponse(process(user, message)));
    }

    private void messageById(HttpExchange exchange, ChatUser user, String path, String method) throws IOException {
        long id = parseId(path, "/api/messages/");
        if ("PUT".equals(method)) {
            EditRequest request = readJson(exchange, EditRequest.class);
            ChatMessage message = ChatMessage.of(ChatCommand.EDIT_MESSAGE, user.id(), ChatMessage.fields(
                    "messageId", Long.toString(id), "text", requireText(request.text(), "text")
            ));
            sendJson(exchange, 200, new ActionResponse(process(user, message)));
        } else if ("DELETE".equals(method)) {
            ChatMessage message = ChatMessage.of(ChatCommand.DELETE_OWN_MESSAGE, user.id(), ChatMessage.fields("messageId", Long.toString(id)));
            sendJson(exchange, 200, new ActionResponse(process(user, message)));
        } else {
            methodNotAllowed(exchange, "PUT, DELETE");
        }
    }

    private void groupAction(HttpExchange exchange, ChatUser user) throws IOException {
        GroupRequest request = readJson(exchange, GroupRequest.class);
        String group = requireText(request.group(), "group");
        ChatCommand command = switch (request.action() == null ? "" : request.action().toLowerCase(Locale.ROOT)) {
            case "create" -> ChatCommand.CREATE_GROUP;
            case "join" -> ChatCommand.JOIN_GROUP;
            default -> throw new ApiException(400, "action must be create or join");
        };
        ChatMessage message = ChatMessage.of(command, user.id(), ChatMessage.fields("group", group));
        sendJson(exchange, 200, new ActionResponse(process(user, message)));
    }

    private void groupMembers(HttpExchange exchange, ChatUser user, String path, String method) throws IOException {
        String group = pathPart(path, "/api/groups/", "/members");
        database.requireGroupMembership(group, user.id());
        if ("GET".equals(method)) {
            sendJson(exchange, 200, new GroupMembersResponse(
                    database.groupMembers(group),
                    database.isGroupOwner(group, user.id())
            ));
            return;
        }
        MemberRequest request = readJson(exchange, MemberRequest.class);
        String username = requireText(request.username(), "username");
        ChatCommand command = switch (method) {
            case "POST" -> ChatCommand.ADD_GROUP_MEMBER;
            case "DELETE" -> ChatCommand.REMOVE_GROUP_MEMBER;
            default -> null;
        };
        if (command == null) {
            methodNotAllowed(exchange, "GET, POST, DELETE");
            return;
        }
        ChatMessage message = ChatMessage.of(command, user.id(), ChatMessage.fields("group", group, "username", username));
        sendJson(exchange, 200, new ActionResponse(process(user, message)));
    }

    private void leaveGroup(HttpExchange exchange, ChatUser user, String path, String method) throws IOException {
        if (!"DELETE".equals(method)) {
            methodNotAllowed(exchange, "DELETE");
            return;
        }
        String group = pathPart(path, "/api/groups/", "/membership");
        ChatMessage message = ChatMessage.of(ChatCommand.LEAVE_GROUP, user.id(), ChatMessage.fields("group", group));
        sendJson(exchange, 200, new ActionResponse(process(user, message)));
    }

    private void deleteGroup(HttpExchange exchange, ChatUser user, String path, String method) throws IOException {
        if (!"DELETE".equals(method)) {
            methodNotAllowed(exchange, "DELETE");
            return;
        }
        String group = pathPart(path, "/api/groups/", "");
        ChatMessage message = ChatMessage.of(ChatCommand.DELETE_GROUP, user.id(), ChatMessage.fields("group", group));
        sendJson(exchange, 200, new ActionResponse(process(user, message)));
    }

    private void friendAction(HttpExchange exchange, ChatUser user, String path, String method) throws IOException {
        String username = pathPart(path, "/api/friends/", "");
        ChatCommand command = switch (method) {
            case "POST" -> ChatCommand.ADD_FRIEND;
            case "DELETE" -> ChatCommand.REMOVE_FRIEND;
            default -> null;
        };
        if (command == null) {
            methodNotAllowed(exchange, "POST, DELETE");
            return;
        }
        ChatMessage message = ChatMessage.of(command, user.id(), ChatMessage.fields("username", username));
        sendJson(exchange, 200, new ActionResponse(process(user, message)));
    }

    private void adminUser(HttpExchange exchange, ChatUser user, String path, String method) throws IOException {
        if (!"POST".equals(method)) {
            methodNotAllowed(exchange, "POST");
            return;
        }
        String suffix = path.substring("/api/admin/users/".length());
        String[] parts = suffix.split("/", -1);
        if (parts.length != 2 || parts[0].isBlank()) {
            throw new ApiException(404, "Endpoint not found");
        }
        String username = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
        ChatCommand command = switch (parts[1]) {
            case "block" -> ChatCommand.ADMIN_BLOCK_USER;
            case "unblock" -> ChatCommand.ADMIN_UNBLOCK_USER;
            default -> throw new ApiException(404, "Endpoint not found");
        };
        ChatMessage message = ChatMessage.of(command, user.id(), ChatMessage.fields("username", username));
        sendJson(exchange, 200, new ActionResponse(process(user, message)));
    }

    private void adminMessage(HttpExchange exchange, ChatUser user, String path, String method) throws IOException {
        if (!"DELETE".equals(method)) {
            methodNotAllowed(exchange, "DELETE");
            return;
        }
        long id = parseId(path, "/api/admin/messages/");
        ChatMessage message = ChatMessage.of(ChatCommand.ADMIN_DELETE_MESSAGE, user.id(), ChatMessage.fields("messageId", Long.toString(id)));
        sendJson(exchange, 200, new ActionResponse(process(user, message)));
    }

    private String process(ChatUser user, ChatMessage request) {
        ServerResult result = chatServer.processHttp(request, user);
        if (!Boolean.parseBoolean(result.response().field("ok"))) {
            throw new ApiException(400, result.response().field("message"));
        }
        eventHub.publish(result.events());
        return result.response().field("message");
    }

    private ChatUser currentUser(HttpExchange exchange) {
        String username = exchange.getPrincipal().getUsername();
        return database.findUser(username)
                .filter(user -> !user.blocked())
                .orElseThrow(() -> new ApiException(401, "Unauthorized"));
    }

    private ChatSummaryDto chatSummary(
            String type,
            String key,
            String title,
            boolean online,
            String chatName,
            int userId,
            boolean owner
    ) {
        StoredMessage last = database.history(chatName, 1).stream().findFirst().orElse(null);
        return new ChatSummaryDto(
                type,
                key,
                title,
                online,
                database.unreadMessages(chatName, userId),
                last == null ? null : last.body(),
                last == null ? null : last.sender(),
                last == null ? null : last.createdAt().toString(),
                owner
        );
    }

    private boolean registryOnline(String username) {
        return chatServer.onlineUsers().contains(username) || browserOnline(username);
    }

    private void markBrowserOnline(String username) {
        browserLastSeen.put(username, System.currentTimeMillis() + BROWSER_PRESENCE_TTL_MILLIS);
    }

    private boolean browserOnline(String username) {
        Long expiresAt = browserLastSeen.get(username);
        if (expiresAt == null) {
            return false;
        }
        if (expiresAt > System.currentTimeMillis()) {
            return true;
        }
        browserLastSeen.remove(username, expiresAt);
        return false;
    }

    private String resolveChat(Map<String, String> query, ChatUser user) {
        String privateWith = query.get("with");
        String group = query.get("group");
        if (privateWith != null && !privateWith.isBlank()) {
            return database.privateChatNameWith(user.id(), privateWith);
        }
        if (group != null && !group.isBlank()) {
            database.requireGroupMembership(group, user.id());
            return group;
        }
        throw new ApiException(400, "with or group is required");
    }

    private void markTyping(String chat, String username) {
        browserTyping.computeIfAbsent(chat, ignored -> new ConcurrentHashMap<>())
                .put(username, System.currentTimeMillis() + TYPING_TTL_MILLIS);
    }

    private List<String> activeTypers(String chat, String currentUsername) {
        Map<String, Long> typers = browserTyping.get(chat);
        if (typers == null) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        typers.entrySet().removeIf(entry -> entry.getValue() <= now);
        if (typers.isEmpty()) {
            browserTyping.remove(chat, typers);
            return List.of();
        }
        return typers.keySet().stream()
                .filter(username -> !username.equals(currentUsername))
                .sorted()
                .toList();
    }

    private void handleStatic(HttpExchange exchange) throws IOException {
        if (handleCorsPreflight(exchange)) {
            return;
        }
        if (!"GET".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        String resource = switch (path) {
            case "/", "/index.html" -> "web/index.html";
            case "/app.css" -> "web/app.css";
            case "/config.js" -> "web/config.js";
            case "/app.js" -> "web/app.js";
            default -> null;
        };
        if (resource == null) {
            sendError(exchange, 404, "Endpoint not found");
            return;
        }
        try (InputStream input = ChatHttpServer.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                sendError(exchange, 404, "Static resource not found");
                return;
            }
            byte[] body = input.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentType(resource));
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        }
    }

    private <T> T readJson(HttpExchange exchange, Class<T> type) throws IOException {
        try (InputStream input = exchange.getRequestBody()) {
            return mapper.readValue(input, type);
        } catch (JsonProcessingException e) {
            throw new ApiException(400, "Invalid JSON body");
        }
    }

    private void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = mapper.writeValueAsBytes(value);
        applyCors(exchange);
        exchange.getResponseHeaders().set("Content-Type", JSON);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private void sendError(HttpExchange exchange, int status, String error) throws IOException {
        sendJson(exchange, status, Map.of("error", error));
    }

    private void methodNotAllowed(HttpExchange exchange, String methods) throws IOException {
        exchange.getResponseHeaders().set("Allow", methods);
        sendError(exchange, 405, "Method not allowed");
    }

    private boolean handleCorsPreflight(HttpExchange exchange) throws IOException {
        applyCors(exchange);
        if (!"OPTIONS".equals(exchange.getRequestMethod())) {
            return false;
        }
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
        return true;
    }

    private void applyCors(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null || !allowedOrigins.contains(origin)) {
            return;
        }
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Authorization, Content-Type, ngrok-skip-browser-warning");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Vary", "Origin");
    }

    private Map<String, String> query(HttpExchange exchange) {
        String raw = exchange.getRequestURI().getRawQuery();
        Map<String, String> values = new HashMap<>();
        if (raw == null || raw.isBlank()) {
            return values;
        }
        for (String item : raw.split("&")) {
            String[] pair = item.split("=", 2);
            String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            String value = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
            values.put(key, value);
        }
        return values;
    }

    private long parseId(String path, String prefix) {
        String text = path.substring(prefix.length());
        if (text.isBlank() || text.contains("/")) {
            throw new ApiException(404, "Endpoint not found");
        }
        try {
            long id = Long.parseLong(text);
            if (id <= 0) {
                throw new ApiException(400, "Message id must be positive");
            }
            return id;
        } catch (NumberFormatException e) {
            throw new ApiException(400, "Message id must be a number");
        }
    }

    private String pathPart(String path, String prefix, String suffix) {
        if (!path.startsWith(prefix) || !path.endsWith(suffix)) {
            throw new ApiException(404, "Endpoint not found");
        }
        int end = path.length() - suffix.length();
        String value = path.substring(prefix.length(), end);
        if (value.isBlank() || value.contains("/")) {
            throw new ApiException(404, "Endpoint not found");
        }
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ApiException(400, field + " is required");
        }
        return value.trim();
    }

    private static String contentType(String resource) {
        return resource.endsWith(".css") ? "text/css; charset=utf-8"
                : resource.endsWith(".js") ? "application/javascript; charset=utf-8"
                : "text/html; charset=utf-8";
    }

    private UserDto userDto(ChatUser user) {
        return new UserDto(user.username(), user.role().name(), registryOnline(user.username()), user.blocked());
    }

    private MessageDto messageDto(StoredMessage message) {
        return new MessageDto(
                message.id(),
                message.chatName(),
                message.sender(),
                message.recipient(),
                message.body(),
                message.createdAt().toString(),
                message.status().name(),
                message.edited(),
                message.deleted()
        );
    }

    public record Credentials(String username, String password) {
    }

    public record AuthResponse(String token, String type, String username, String role) {
    }

    public record UserDto(String username, String role, boolean online, boolean blocked) {
    }

    public record ChatsResponse(
            UserDto currentUser,
            List<UserDto> users,
            List<String> friends,
            List<String> groups,
            List<ChatSummaryDto> chats
    ) {
    }

    public record ChatSummaryDto(
            String type,
            String key,
            String title,
            boolean online,
            int unreadCount,
            String lastText,
            String lastSender,
            String lastCreatedAt,
            boolean owner
    ) {
    }

    public record MessageRequest(String to, String group, String text) {
    }

    public record TypingRequest(String to, String group) {
    }

    public record MessageDto(
            long id,
            String chat,
            String sender,
            String recipient,
            String text,
            String createdAt,
            String status,
            boolean edited,
            boolean deleted
    ) {
    }

    public record MessagesResponse(List<MessageDto> messages) {
    }

    public record TypingResponse(List<String> users) {
    }

    public record GroupRequest(String action, String group) {
    }

    public record MemberRequest(String username) {
    }

    public record GroupMembersResponse(List<String> members, boolean owner) {
    }

    public record EditRequest(String text) {
    }

    public record ActionResponse(String message) {
    }

    private static final class ApiException extends RuntimeException {
        private final int status;

        private ApiException(int status, String message) {
            super(message);
            this.status = status;
        }

        private int status() {
            return status;
        }
    }
}
