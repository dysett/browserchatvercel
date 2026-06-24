package ua.finalproject.chat.server;

import ua.finalproject.chat.db.ChatDatabase;
import ua.finalproject.chat.db.ChatUser;
import ua.finalproject.chat.db.MessageStatus;
import ua.finalproject.chat.db.StoredMessage;
import ua.finalproject.chat.protocol.ChatCommand;
import ua.finalproject.chat.protocol.ChatMessage;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Обробник команд чату.
 * Він приймає ChatMessage, виконує потрібну дію через базу даних і повертає відповідь або події для інших користувачів.
 */
public final class ChatProcessor {
    private static final String GENERAL_CHAT = "general";
    private static final DateTimeFormatter MESSAGE_TIME = DateTimeFormatter.ofPattern("HH:mm")
            .withZone(ZoneId.systemDefault());

    private final ChatDatabase database;
    private final ClientRegistry registry;

    public ChatProcessor(ChatDatabase database, ClientRegistry registry) {
        this.database = Objects.requireNonNull(database, "database");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Визначає команду повідомлення та викликає відповідний приватний метод.
     */
    public ServerResult process(ChatMessage request, ChatUser currentUser) {
        try {
            return switch (request.command()) {
                case REGISTER -> register(request);
                case LOGIN -> login(request);
                case USERS -> users(request, requireUser(currentUser));
                case SEND_PUBLIC -> sendPublic(request, requireUser(currentUser));
                case SEND_PRIVATE -> sendPrivate(request, requireUser(currentUser));
                case CREATE_GROUP -> createGroup(request, requireUser(currentUser));
                case JOIN_GROUP -> joinGroup(request, requireUser(currentUser));
                case SEND_GROUP -> sendGroup(request, requireUser(currentUser));
                case HISTORY -> history(request, requireUser(currentUser));
                case TYPING -> typing(request, requireUser(currentUser));
                case ADD_GROUP_MEMBER -> addGroupMember(request, requireUser(currentUser));
                case REMOVE_GROUP_MEMBER -> removeGroupMember(request, requireUser(currentUser));
                case LEAVE_GROUP -> leaveGroup(request, requireUser(currentUser));
                case DELETE_GROUP -> deleteGroup(request, requireUser(currentUser));
                case ADD_FRIEND -> addFriend(request, requireUser(currentUser));
                case REMOVE_FRIEND -> removeFriend(request, requireUser(currentUser));
                case EDIT_MESSAGE -> editMessage(request, requireUser(currentUser));
                case DELETE_OWN_MESSAGE -> deleteOwnMessage(request, requireUser(currentUser));
                case ADMIN_DELETE_MESSAGE -> deleteMessage(request, requireUser(currentUser));
                case ADMIN_BLOCK_USER -> blockUser(request, requireUser(currentUser));
                case ADMIN_UNBLOCK_USER -> unblockUser(request, requireUser(currentUser));
                case ADMIN_DELETE_USER -> deleteUser(request, requireUser(currentUser));
                case MARK_READ -> markRead(request, requireUser(currentUser));
                case LOGOUT -> ServerResult.response(ok(request, "Logged out"));
                default -> ServerResult.response(error(request, "Unsupported client command"));
            };
        } catch (RuntimeException e) {
            return ServerResult.response(error(request, e.getMessage()));
        }
    }

    private ServerResult register(ChatMessage request) {
        ChatUser user = database.register(request.requiredField("username"), request.requiredField("password"));
        return ServerResult.response(authResponse(request, user, "Registered"))
                .withAuthenticatedUser(user);
    }

    private ServerResult login(ChatMessage request) {
        ChatUser user = database.authenticate(request.requiredField("username"), request.requiredField("password"));
        return ServerResult.response(authResponse(request, user, "Logged in"))
                .withAuthenticatedUser(user);
    }

    private ServerResult users(ChatMessage request, ChatUser currentUser) {
        String allUsers = database.listUsersDetailed().stream()
                .map(user -> user.username()
                        + "(" + user.role()
                        + (user.blocked() ? ",blocked" : "")
                        + (registry.isOnline(user.username()) ? ",online" : "")
                        + ")")
                .collect(Collectors.joining(","));
        String onlineUsers = String.join(",", registry.onlineUsers());
        String groups = String.join(",", database.groupsForUser(currentUser.id()));
        return ServerResult.response(response(request, true, Map.of(
                "message", "Users loaded",
                "users", allUsers,
                "online", onlineUsers,
                "groups", groups,
                "user", currentUser.username()
        )));
    }

    /**
     * Відправляє повідомлення у груповий чат і формує події для всіх учасників групи.
     */
    private ServerResult sendPublic(ChatMessage request, ChatUser currentUser) {
        StoredMessage message = database.savePublicMessage(currentUser.id(), GENERAL_CHAT, request.requiredField("text"));
        List<OutboundEvent> events = eventsForMembers(GENERAL_CHAT, message);
        return ServerResult.response(ok(request, "Public message sent"))
                .withEvents(events);
    }

    /**
     * Відправляє приватне повідомлення конкретному користувачу.
     */
    private ServerResult sendPrivate(ChatMessage request, ChatUser currentUser) {
        String recipient = request.requiredField("to");
        StoredMessage message = database.savePrivateMessage(
                currentUser.id(),
                recipient,
                request.requiredField("text"),
                optionalReplyTo(request)
        );
        MessageStatus status = registry.isOnline(recipient) ? MessageStatus.DELIVERED : MessageStatus.SENT;
        database.updateMessageStatus(message.id(), status);
        ChatMessage event = eventMessage(ChatCommand.EVENT_MESSAGE, message, status, "created");
        return ServerResult.response(ok(request, "Private message sent"))
                .withEvents(List.of(
                        OutboundEvent.toUser(recipient, event),
                        OutboundEvent.toUser(currentUser.username(), event)
                ));
    }

    /**
     * Створює групу від імені поточного користувача.
     */
    private ServerResult createGroup(ChatMessage request, ChatUser currentUser) {
        String group = request.requiredField("group");
        database.createGroupForUser(group, currentUser.id());
        StoredMessage systemMessage = database.saveGroupEvent(group, currentUser, "created", null);
        return ServerResult.response(ok(request, "Group created"))
                .withEvents(eventsForMembers(group, systemMessage));
    }

    private ServerResult joinGroup(ChatMessage request, ChatUser currentUser) {
        String group = request.requiredField("group");
        database.joinGroup(currentUser.id(), group);
        StoredMessage systemMessage = database.saveGroupEvent(group, currentUser, "joined", null);
        return ServerResult.response(ok(request, "Joined group"))
                .withEvents(eventsForMembers(group, systemMessage));
    }

    private ServerResult sendGroup(ChatMessage request, ChatUser currentUser) {
        StoredMessage message = database.savePublicMessage(
                currentUser.id(),
                request.requiredField("group"),
                request.requiredField("text"),
                optionalReplyTo(request)
        );
        List<OutboundEvent> events = eventsForMembers(request.requiredField("group"), message);
        return ServerResult.response(ok(request, "Group message sent"))
                .withEvents(events);
    }

    private ServerResult typing(ChatMessage request, ChatUser currentUser) {
        String recipient = request.field("to");
        String group = request.field("group");
        if ((recipient == null || recipient.isBlank()) && (group == null || group.isBlank())) {
            throw new IllegalArgumentException("Typing target is required");
        }

        ChatMessage event = ChatMessage.of(ChatCommand.EVENT_TYPING, 0, ChatMessage.fields(
                "from", currentUser.username(),
                "to", recipient,
                "chat", group,
                "createdAt", Instant.now().toString(),
                "typing", "true"
        ));

        if (recipient != null && !recipient.isBlank()) {
            database.privateChatNameWith(currentUser.id(), recipient);
            return ServerResult.response(ok(request, "Typing delivered"))
                    .withEvents(List.of(OutboundEvent.toUser(recipient, event)));
        }

        database.requireGroupMembership(group, currentUser.id());
        List<OutboundEvent> events = database.groupMembers(group).stream()
                .filter(member -> !member.equals(currentUser.username()))
                .map(member -> OutboundEvent.toUser(member, event))
                .toList();
        return ServerResult.response(ok(request, "Typing delivered"))
                .withEvents(events);
    }

    private ServerResult addGroupMember(ChatMessage request, ChatUser currentUser) {
        String group = request.requiredField("group");
        String username = request.requiredField("username");
        database.addGroupMember(group, username, currentUser);
        StoredMessage systemMessage = database.saveGroupEvent(group, currentUser, "added", username);
        return ServerResult.response(ok(request, "Group member added"))
                .withEvents(eventsForMembers(group, systemMessage));
    }

    private ServerResult removeGroupMember(ChatMessage request, ChatUser currentUser) {
        String group = request.requiredField("group");
        String username = request.requiredField("username");
        database.removeGroupMember(group, username, currentUser);
        StoredMessage systemMessage = database.saveGroupEvent(group, currentUser, "removed", username);
        ChatMessage removedEvent = ChatMessage.of(ChatCommand.EVENT_STATUS, 0, ChatMessage.fields(
                "state", "group-removed",
                "group", group,
                "message", "Removed from group " + group
        ));
        List<OutboundEvent> events = new ArrayList<>(eventsForMembers(group, systemMessage));
        events.add(OutboundEvent.toUser(username, removedEvent));
        return ServerResult.response(ok(request, "Group member removed"))
                .withEvents(events);
    }

    private ServerResult leaveGroup(ChatMessage request, ChatUser currentUser) {
        String group = request.requiredField("group");
        database.leaveGroup(group, currentUser);
        StoredMessage systemMessage = database.saveGroupEvent(group, currentUser, "left", null);
        ChatMessage leftEvent = groupStatusEvent("group-left", group, currentUser.username() + " left group " + group);
        List<OutboundEvent> events = new ArrayList<>(eventsForMembers(group, systemMessage));
        events.add(OutboundEvent.toUser(currentUser.username(), leftEvent));
        return ServerResult.response(ok(request, "Left group"))
                .withEvents(events);
    }

    private ServerResult deleteGroup(ChatMessage request, ChatUser currentUser) {
        String group = request.requiredField("group");
        List<String> members = database.groupMembers(group);
        database.deleteGroup(group, currentUser);
        ChatMessage event = groupStatusEvent("group-deleted", group, "Group deleted: " + group);
        return ServerResult.response(ok(request, "Group deleted"))
                .withEvents(statusEvents(members, event));
    }

    private ServerResult addFriend(ChatMessage request, ChatUser currentUser) {
        String username = request.requiredField("username");
        database.addFriend(currentUser.id(), username);
        ChatMessage event = ChatMessage.of(ChatCommand.EVENT_STATUS, 0, ChatMessage.fields(
                "state", "friend-added",
                "username", username,
                "message", currentUser.username() + " added you as a friend"
        ));
        return ServerResult.response(ok(request, "Friend added"))
                .withEvents(List.of(
                        OutboundEvent.toUser(currentUser.username(), event),
                        OutboundEvent.toUser(username, event)
                ));
    }

    private ServerResult removeFriend(ChatMessage request, ChatUser currentUser) {
        String username = request.requiredField("username");
        database.removeFriend(currentUser.id(), username);
        ChatMessage event = ChatMessage.of(ChatCommand.EVENT_STATUS, 0, ChatMessage.fields(
                "state", "friend-removed",
                "username", username,
                "message", currentUser.username() + " removed you from friends"
        ));
        return ServerResult.response(ok(request, "Friend removed"))
                .withEvents(List.of(
                        OutboundEvent.toUser(currentUser.username(), event),
                        OutboundEvent.toUser(username, event)
                ));
    }

    private ServerResult editMessage(ChatMessage request, ChatUser currentUser) {
        StoredMessage message = database.editOwnMessage(
                Long.parseLong(request.requiredField("messageId")),
                currentUser,
                request.requiredField("text")
        );
        return ServerResult.response(ok(request, "Message edited"))
                .withEvents(eventsForMessageUpdate(message, "edited"));
    }

    private ServerResult deleteOwnMessage(ChatMessage request, ChatUser currentUser) {
        StoredMessage message = database.deleteOwnMessage(
                Long.parseLong(request.requiredField("messageId")),
                currentUser
        );
        return ServerResult.response(ok(request, "Message deleted"))
                .withEvents(eventsForMessageUpdate(message, "deleted"));
    }

    /**
     * Формує текстову історію чату для TCP-клієнта.
     */
    private ServerResult history(ChatMessage request, ChatUser currentUser) {
        String chat = resolveChat(request, currentUser);
        int limit = request.field("limit") == null ? 20 : Integer.parseInt(request.field("limit"));
        List<StoredMessage> readMessages = database.markChatRead(chat, currentUser.id());
        List<StoredMessage> messages = database.historyForUser(chat, currentUser.id(), limit);
        String history = messages.stream()
                .map(this::historyLine)
                .collect(Collectors.joining("\n"));
        String historyData = messages.stream()
                .map(this::historyDataLine)
                .collect(Collectors.joining("\n"));
        return ServerResult.response(response(request, true, Map.of(
                "message", "History loaded",
                "chat", chat,
                "history", history,
                "historyData", historyData,
                "user", currentUser.username()
        ))).withEvents(readEvents(readMessages));
    }

    private Long optionalReplyTo(ChatMessage request) {
        String value = request.field("replyTo");
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("replyTo must be a message id");
        }
    }

    private ServerResult deleteMessage(ChatMessage request, ChatUser currentUser) {
        StoredMessage message = database.deleteMessage(Long.parseLong(request.requiredField("messageId")), currentUser);
        return ServerResult.response(ok(request, "Message deleted"))
                .withEvents(eventsForMessageUpdate(message, "deleted"));
    }

    private ServerResult blockUser(ChatMessage request, ChatUser currentUser) {
        String username = request.requiredField("username");
        database.blockUser(username, currentUser);
        return ServerResult.response(ok(request, "User blocked"))
                .withEvents(List.of(userStatusEvent("user-blocked", username)));
    }

    private ServerResult unblockUser(ChatMessage request, ChatUser currentUser) {
        String username = request.requiredField("username");
        database.unblockUser(username, currentUser);
        return ServerResult.response(ok(request, "User unblocked"))
                .withEvents(List.of(userStatusEvent("user-unblocked", username)));
    }

    private ServerResult deleteUser(ChatMessage request, ChatUser currentUser) {
        String username = request.requiredField("username");
        database.deleteUserPermanently(username, currentUser);
        return ServerResult.response(ok(request, "User permanently deleted"))
                .withEvents(List.of(userStatusEvent("user-deleted", username)));
    }

    private ServerResult markRead(ChatMessage request, ChatUser currentUser) {
        String chat = resolveChat(request, currentUser);
        List<StoredMessage> readMessages = database.markChatRead(chat, currentUser.id());
        return ServerResult.response(ok(request, "Messages marked as read"))
                .withEvents(readEvents(readMessages));
    }

    private String resolveChat(ChatMessage request, ChatUser currentUser) {
        if (request.field("privateWith") != null) {
            return database.privateChatNameWith(currentUser.id(), request.requiredField("privateWith"));
        }
        String group = request.field("chat");
        if (group == null) {
            return GENERAL_CHAT;
        }
        database.requireGroupMembership(group, currentUser.id());
        return group;
    }

    private List<OutboundEvent> statusEvents(List<String> members, ChatMessage event) {
        return members.stream().map(member -> OutboundEvent.toUser(member, event)).toList();
    }

    private ChatMessage groupStatusEvent(String state, String group, String message) {
        return ChatMessage.of(ChatCommand.EVENT_STATUS, 0, ChatMessage.fields(
                "state", state,
                "group", group,
                "message", message
        ));
    }

    private OutboundEvent userStatusEvent(String state, String username) {
        ChatMessage event = ChatMessage.of(ChatCommand.EVENT_STATUS, 0, ChatMessage.fields(
                "state", state,
                "username", username,
                "message", "User updated: " + username
        ));
        return OutboundEvent.broadcast(event);
    }

    private List<OutboundEvent> eventsForMembers(String chatName, StoredMessage message) {
        List<String> members = database.groupMembers(chatName);
        boolean delivered = members.stream().anyMatch(registry::isOnline);
        MessageStatus status = delivered ? MessageStatus.DELIVERED : MessageStatus.SENT;
        database.updateMessageStatus(message.id(), status);
        ChatMessage event = eventMessage(ChatCommand.EVENT_MESSAGE, message, status, "created");
        return members.stream()
                .map(member -> OutboundEvent.toUser(member, event))
                .toList();
    }

    private List<OutboundEvent> eventsForMessageUpdate(StoredMessage message, String action) {
        ChatMessage event = eventMessage(ChatCommand.EVENT_MESSAGE_UPDATE, message, message.status(), action);
        if (message.recipient() != null) {
            return List.of(
                    OutboundEvent.toUser(message.recipient(), event),
                    OutboundEvent.toUser(message.sender(), event)
            );
        }
        return database.groupMembers(message.chatName()).stream()
                .map(member -> OutboundEvent.toUser(member, event))
                .toList();
    }

    private List<OutboundEvent> readEvents(List<StoredMessage> messages) {
        return messages.stream()
                .flatMap(message -> eventsForMessageUpdate(message, "read").stream())
                .toList();
    }

    private ChatMessage eventMessage(ChatCommand command, StoredMessage message, MessageStatus status, String action) {
        return ChatMessage.of(command, 0, ChatMessage.fields(
                "id", Long.toString(message.id()),
                "chat", message.chatName(),
                "from", message.sender(),
                "to", message.recipient(),
                "text", message.body(),
                "createdAt", message.createdAt().toString(),
                "status", status.name(),
                "edited", Boolean.toString(message.edited()),
                "deleted", Boolean.toString(message.deleted()),
                "system", Boolean.toString(message.system()),
                "action", action
        ));
    }

    private String historyLine(StoredMessage message) {
        if (message.system()) {
            return MESSAGE_TIME.format(message.createdAt()) + " " + message.body();
        }
        String edited = message.edited() && !message.deleted() ? " (edited)" : "";
        return MESSAGE_TIME.format(message.createdAt()) + " " + message.sender() + ": " + message.body() + edited;
    }

    private String historyDataLine(StoredMessage message) {
        return message.id()
                + "|" + message.createdAt()
                + "|" + message.sender()
                + "|" + Objects.toString(message.recipient(), "")
                + "|" + message.chatName()
                + "|" + message.edited()
                + "|" + message.deleted()
                + "|" + message.status()
                + "|" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(message.body().getBytes(StandardCharsets.UTF_8));
    }

    private ChatMessage ok(ChatMessage request, String message) {
        return response(request, true, Map.of(
                "message", message
        ));
    }

    private ChatMessage authResponse(ChatMessage request, ChatUser user, String message) {
        return response(request, true, Map.of(
                "message", message,
                "userId", Integer.toString(user.id()),
                "username", user.username(),
                "role", user.role().name()
        ));
    }

    private ChatMessage error(ChatMessage request, String message) {
        return response(request, false, Map.of(
                "message", message == null ? "Unknown error" : message
        ));
    }

    private ChatMessage response(ChatMessage request, boolean ok, Map<String, String> fields) {
        java.util.LinkedHashMap<String, String> result = new java.util.LinkedHashMap<>();
        result.put("ok", Boolean.toString(ok));
        result.put("request", request.command().name());
        result.putAll(fields);
        return ChatMessage.of(ChatCommand.RESPONSE, request.userId(), result);
    }

    private static ChatUser requireUser(ChatUser user) {
        if (user == null) {
            throw new IllegalArgumentException("Login is required");
        }
        return user;
    }
}
