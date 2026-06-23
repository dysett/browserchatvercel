package ua.finalproject.chat.db;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

public final class ChatDatabase implements AutoCloseable {
    private static final String GENERAL_CHAT = "general";
    private static final int PBKDF2_ITERATIONS = 120_000;
    private static final int PBKDF2_BITS = 256;
    private static final String DEFAULT_REACTION = "❤️";
    private static final Set<String> ALLOWED_REACTIONS = Set.of("❤️", "👍", "😂", "😢", "🔥", "😮", "👏");

    private final Connection connection;
    private final SecureRandom random = new SecureRandom();

    public record UserAvatar(byte[] content, String contentType) {
    }

    public ChatDatabase(String jdbcUrl) {
        try {
            this.connection = DriverManager.getConnection(Objects.requireNonNull(jdbcUrl, "jdbcUrl"));
            init();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot open chat database", e);
        }
    }

    public synchronized ChatUser register(String username, String password) {
        String login = requireName(username, "username");
        String salt = createSalt();
        String hash = passwordHash(login, requireName(password, "password"), salt);
        UserRole role = "admin".equalsIgnoreCase(login) ? UserRole.ADMIN : UserRole.USER;

        return inTransaction(() -> {
            String sql = """
                    insert into users(username, password_hash, password_salt, role, blocked, created_at)
                    values (?, ?, ?, ?, 0, ?)
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, login);
                statement.setString(2, hash);
                statement.setString(3, salt);
                statement.setString(4, role.name());
                statement.setString(5, Instant.now().toString());
                statement.executeUpdate();

                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (keys.next()) {
                        ChatUser user = new ChatUser(keys.getInt(1), login, role, false, "", false, DEFAULT_REACTION);
                        joinGroup(user.id(), GENERAL_CHAT);
                        return user;
                    }
                }
                throw new IllegalStateException("User id was not generated");
            } catch (SQLException e) {
                throw new IllegalArgumentException("User already exists or cannot be created: " + login, e);
            }
        });
    }

    public synchronized ChatUser authenticate(String username, String password) {
        String login = requireName(username, "username");
        String rawPassword = requireName(password, "password");

        try (PreparedStatement statement = connection.prepareStatement("select * from users where username = ?")) {
            statement.setString(1, login);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalArgumentException("Invalid username or password");
                }
                String salt = resultSet.getString("password_salt");
                String expectedHash = resultSet.getString("password_hash");
                String actualHash = salt == null || salt.isBlank()
                        ? legacyPasswordHash(login, rawPassword)
                        : passwordHash(login, rawPassword, salt);
                if (!expectedHash.equals(actualHash)) {
                    throw new IllegalArgumentException("Invalid username or password");
                }
                if (resultSet.getInt("blocked") != 0) {
                    throw new IllegalArgumentException("User is blocked");
                }
                return readUser(resultSet);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot authenticate user", e);
        }
    }

    public synchronized Optional<ChatUser> findUser(String username) {
        String login = requireName(username, "username");
        try (PreparedStatement statement = connection.prepareStatement("select * from users where username = ?")) {
            statement.setString(1, login);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(readUser(resultSet)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot find user", e);
        }
    }

    public synchronized ChatUser updateProfile(
            ChatUser user,
            String description,
            byte[] avatar,
            String avatarContentType,
            boolean removeAvatar,
            String quickReaction
    ) {
        String profileDescription = cleanDescription(description);
        String safeQuickReaction = normalizeReaction(quickReaction, DEFAULT_REACTION);
        try {
            if (avatar != null) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        update users
                        set description = ?, avatar_data = ?, avatar_content_type = ?, quick_reaction = ?
                        where id = ?
                        """)) {
                    statement.setString(1, profileDescription);
                    statement.setBytes(2, avatar);
                    statement.setString(3, avatarContentType);
                    statement.setString(4, safeQuickReaction);
                    statement.setInt(5, user.id());
                    statement.executeUpdate();
                }
            } else if (removeAvatar) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        update users
                        set description = ?, avatar_data = null, avatar_content_type = null, quick_reaction = ?
                        where id = ?
                        """)) {
                    statement.setString(1, profileDescription);
                    statement.setString(2, safeQuickReaction);
                    statement.setInt(3, user.id());
                    statement.executeUpdate();
                }
            } else {
                try (PreparedStatement statement = connection.prepareStatement(
                        "update users set description = ?, quick_reaction = ? where id = ?")) {
                    statement.setString(1, profileDescription);
                    statement.setString(2, safeQuickReaction);
                    statement.setInt(3, user.id());
                    statement.executeUpdate();
                }
            }
            return findUserById(user.id());
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot update profile", e);
        }
    }

    public synchronized Optional<UserAvatar> avatarForUser(int userId) {
        try (PreparedStatement statement = connection.prepareStatement("""
                select avatar_data, avatar_content_type
                from users
                where id = ? and avatar_data is not null
                """)) {
            statement.setInt(1, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(new UserAvatar(
                            resultSet.getBytes("avatar_data"),
                            resultSet.getString("avatar_content_type")
                    ));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read profile avatar", e);
        }
    }

    public synchronized List<String> listUsers() {
        try (PreparedStatement statement = connection.prepareStatement("select username from users order by username");
             ResultSet resultSet = statement.executeQuery()) {
            List<String> users = new ArrayList<>();
            while (resultSet.next()) {
                users.add(resultSet.getString("username"));
            }
            return users;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot list users", e);
        }
    }

    public synchronized List<ChatUser> listUsersDetailed() {
        try (PreparedStatement statement = connection.prepareStatement("select * from users order by username");
             ResultSet resultSet = statement.executeQuery()) {
            List<ChatUser> users = new ArrayList<>();
            while (resultSet.next()) {
                users.add(readUser(resultSet));
            }
            return users;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot list users", e);
        }
    }

    public synchronized List<ChatUser> searchUsers(String query, int excludedUserId) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String sql = """
                select *
                from users
                where id <> ?
                  and blocked = 0
                  and lower(username) like lower(?)
                order by username
                limit 20
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, excludedUserId);
            statement.setString(2, "%" + query.trim() + "%");
            try (ResultSet resultSet = statement.executeQuery()) {
                List<ChatUser> users = new ArrayList<>();
                while (resultSet.next()) {
                    users.add(readUser(resultSet));
                }
                return users;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot search users", e);
        }
    }

    public synchronized void blockUser(String username, ChatUser actor) {
        changeBlocked(username, actor, true);
    }

    public synchronized void unblockUser(String username, ChatUser actor) {
        changeBlocked(username, actor, false);
    }

    public synchronized void deleteUserPermanently(String username, ChatUser actor) {
        requireAdmin(actor);
        String login = requireName(username, "username");
        if (actor.username().equals(login)) {
            throw new IllegalArgumentException("Admin cannot delete himself");
        }
        ChatUser user = findUser(login)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + login));

        inTransaction(() -> {
            for (long groupId : groupIdsOwnedBy(user.id())) {
                deleteByChatId("delete from messages where chat_id = ?", groupId);
                deleteByChatId("delete from chat_members where chat_id = ?", groupId);
                deleteByChatId("delete from chats where id = ?", groupId);
            }
            deleteMessagesForUser(user.id());
            deleteFriendshipsForUser(user.id());
            deleteMembershipsForUser(user.id());
            deleteEmptyPrivateChats();
            try (PreparedStatement statement = connection.prepareStatement("delete from users where id = ?")) {
                statement.setInt(1, user.id());
                statement.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("Cannot delete user", e);
            }
            return null;
        });
    }

    public synchronized void createGroup(String groupName) {
        String group = requireName(groupName, "groupName");
        inTransaction(() -> {
            if (chatExists(group)) {
                throw new IllegalArgumentException("Group already exists: " + group);
            }
            createGroupRecord(group, null);
            return null;
        });
    }

    public synchronized void createGroupForUser(String groupName, int ownerId) {
        String group = requireName(groupName, "groupName");
        inTransaction(() -> {
            if (chatExists(group)) {
                throw new IllegalArgumentException("Group already exists: " + group);
            }
            long groupId = createGroupRecord(group, ownerId);
            addMembership(groupId, ownerId);
            return null;
        });
    }

    public synchronized void joinGroup(int userId, String groupName) {
        long chatId = requireGroupId(requireName(groupName, "groupName"));
        addMembership(chatId, userId);
    }

    public synchronized void leaveGroup(String groupName, ChatUser user) {
        String group = requireName(groupName, "groupName");
        long groupId = requireGroupId(group);
        if (isGroupOwner(group, user.id())) {
            throw new IllegalArgumentException("Group owner must delete the group instead of leaving it");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "delete from chat_members where chat_id = ? and user_id = ?")) {
            statement.setLong(1, groupId);
            statement.setInt(2, user.id());
            if (statement.executeUpdate() == 0) {
                throw new IllegalArgumentException("You are not a member of group: " + group);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot leave group", e);
        }
    }

    public synchronized void deleteGroup(String groupName, ChatUser actor) {
        String group = requireName(groupName, "groupName");
        long groupId = requireGroupId(group);
        requireGroupOwner(group, actor.id());
        inTransaction(() -> {
            deleteByChatId("delete from messages where chat_id = ?", groupId);
            deleteByChatId("delete from chat_members where chat_id = ?", groupId);
            deleteByChatId("delete from chats where id = ?", groupId);
            return null;
        });
    }

    public synchronized boolean isGroupOwner(String groupName, int userId) {
        String sql = "select 1 from chats where name = ? and type = 'GROUP' and owner_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, requireName(groupName, "groupName"));
            statement.setInt(2, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot check group owner", e);
        }
    }

    public synchronized void requireGroupMembership(String groupName, int userId) {
        long groupId = requireGroupId(requireName(groupName, "groupName"));
        if (!hasMembership(groupId, userId)) {
            throw new IllegalArgumentException("You are not a member of group: " + groupName);
        }
    }

    private void addMembership(long chatId, int userId) {
        String sql = """
                insert or ignore into chat_members(chat_id, user_id, joined_at, joined_after_message_id)
                values (?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, chatId);
            statement.setInt(2, userId);
            statement.setString(3, Instant.now().toString());
            statement.setLong(4, lastMessageId(chatId));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot join group", e);
        }
    }

    private long lastMessageId(long chatId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "select coalesce(max(id), 0) from messages where chat_id = ?")) {
            statement.setLong(1, chatId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read group history boundary", e);
        }
    }

    public synchronized void addGroupMember(String groupName, String username, ChatUser actor) {
        requireGroupOwner(groupName, actor.id());
        ChatUser user = findUser(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));
        joinGroup(user.id(), groupName);
    }

    public synchronized void removeGroupMember(String groupName, String username, ChatUser actor) {
        requireGroupOwner(groupName, actor.id());
        ChatUser user = findUser(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));
        if (user.id() == actor.id()) {
            throw new IllegalArgumentException("Group owner cannot remove himself");
        }
        String sql = """
                delete from chat_members
                where chat_id = (select id from chats where name = ?)
                  and user_id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, requireName(groupName, "groupName"));
            statement.setInt(2, user.id());
            if (statement.executeUpdate() == 0) {
                throw new IllegalArgumentException("User is not a group member: " + username);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot remove group member", e);
        }
    }

    public synchronized List<String> groupMembers(String groupName) {
        String sql = """
                select u.username
                from chat_members cm
                join chats c on c.id = cm.chat_id
                join users u on u.id = cm.user_id
                where c.name = ?
                order by u.username
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, requireName(groupName, "groupName"));
            try (ResultSet resultSet = statement.executeQuery()) {
                List<String> members = new ArrayList<>();
                while (resultSet.next()) {
                    members.add(resultSet.getString("username"));
                }
                return members;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot list group members", e);
        }
    }

    public synchronized List<String> groupsForUser(int userId) {
        String sql = """
                select c.name
                from chat_members cm
                join chats c on c.id = cm.chat_id
                where cm.user_id = ?
                  and c.type = 'GROUP'
                  and c.name <> ?
                order by c.name
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, userId);
            statement.setString(2, GENERAL_CHAT);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<String> groups = new ArrayList<>();
                while (resultSet.next()) {
                    groups.add(resultSet.getString("name"));
                }
                return groups;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot list user groups", e);
        }
    }

    public synchronized StoredMessage savePublicMessage(int senderId, String groupName, String body) {
        return savePublicMessage(senderId, groupName, body, null);
    }

    public synchronized StoredMessage savePublicMessage(int senderId, String groupName, String body, Long replyToMessageId) {
        String chat = requireName(groupName, "groupName");
        String messageBody = requireBody(body);
        return inTransaction(() -> {
            requireGroupMembership(chat, senderId);
            return saveMessage(chat, senderId, null, messageBody, replyToMessageId);
        });
    }

    public synchronized StoredMessage savePrivateMessage(int senderId, String recipientUsername, String body) {
        return savePrivateMessage(senderId, recipientUsername, body, null);
    }

    public synchronized StoredMessage savePrivateMessage(
            int senderId,
            String recipientUsername,
            String body,
            Long replyToMessageId
    ) {
        String messageBody = requireBody(body);
        return inTransaction(() -> {
            ChatUser recipient = findUser(recipientUsername)
                    .orElseThrow(() -> new IllegalArgumentException("Recipient not found: " + recipientUsername));
            requireFriends(senderId, recipient.id());
            String chatName = privateChatName(senderId, recipient.id());
            ensureChat(chatName, "PRIVATE");
            return saveMessage(chatName, senderId, recipient.id(), messageBody, replyToMessageId);
        });
    }

    public synchronized String privateChatNameWith(int firstUserId, String secondUsername) {
        ChatUser secondUser = findUser(secondUsername)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + secondUsername));
        requireFriends(firstUserId, secondUser.id());
        return privateChatName(firstUserId, secondUser.id());
    }

    public synchronized List<ChatUser> friendsForUser(int userId) {
        String sql = """
                select u.*
                from friendships f
                join users u on u.id = f.friend_id
                where f.user_id = ?
                order by u.username
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<ChatUser> friends = new ArrayList<>();
                while (resultSet.next()) {
                    friends.add(readUser(resultSet));
                }
                return friends;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot list friends", e);
        }
    }

    public synchronized void addFriend(int userId, String username) {
        ChatUser friend = findUser(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));
        if (friend.id() == userId) {
            throw new IllegalArgumentException("You cannot add yourself as a friend");
        }
        inTransaction(() -> {
            addFriendLink(userId, friend.id());
            addFriendLink(friend.id(), userId);
            return null;
        });
    }

    public synchronized void removeFriend(int userId, String username) {
        ChatUser friend = findUser(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));
        inTransaction(() -> {
            removeFriendLink(userId, friend.id());
            removeFriendLink(friend.id(), userId);
            deletePrivateChat(privateChatName(userId, friend.id()));
            return null;
        });
    }

    public synchronized List<StoredMessage> history(String chatName, int limit) {
        String chat = requireName(chatName, "chatName");
        int safeLimit = Math.max(1, Math.min(limit, 100));
        String sql = """
                select m.id, c.name as chat_name, u.username as sender, r.username as recipient,
                       m.body, m.created_at, m.deleted, m.status, m.edited,
                       m.reply_to_message_id as reply_to_id, reply_sender.username as reply_sender,
                       reply.body as reply_body, reply.deleted as reply_deleted
                from messages m
                join chats c on c.id = m.chat_id
                join users u on u.id = m.sender_id
                left join users r on r.id = m.recipient_id
                left join messages reply on reply.id = m.reply_to_message_id
                left join users reply_sender on reply_sender.id = reply.sender_id
                where c.name = ?
                order by m.id desc
                limit ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, chat);
            statement.setInt(2, safeLimit);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<StoredMessage> messages = new ArrayList<>();
                while (resultSet.next()) {
                    messages.add(readMessage(resultSet));
                }
                Collections.reverse(messages);
                return messages;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read history", e);
        }
    }

    public synchronized List<StoredMessage> historyForUser(String chatName, int userId, int limit) {
        String chat = requireName(chatName, "chatName");
        int safeLimit = Math.max(1, Math.min(limit, 100));
        String sql = """
                select m.id, c.name as chat_name, u.username as sender, r.username as recipient,
                       m.body, m.created_at, m.deleted, m.status, m.edited,
                       m.reply_to_message_id as reply_to_id, reply_sender.username as reply_sender,
                       reply.body as reply_body, reply.deleted as reply_deleted
                from messages m
                join chats c on c.id = m.chat_id
                join users u on u.id = m.sender_id
                left join users r on r.id = m.recipient_id
                left join messages reply on reply.id = m.reply_to_message_id
                left join users reply_sender on reply_sender.id = reply.sender_id
                left join chat_members cm on cm.chat_id = c.id and cm.user_id = ?
                where c.name = ?
                  and (c.type <> 'GROUP' or m.id > cm.joined_after_message_id)
                order by m.id desc
                limit ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, userId);
            statement.setString(2, chat);
            statement.setInt(3, safeLimit);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<StoredMessage> messages = new ArrayList<>();
                while (resultSet.next()) {
                    messages.add(readMessage(resultSet));
                }
                Collections.reverse(messages);
                return messages;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read user history", e);
        }
    }

    public synchronized List<StoredMessage> recentMessages(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        String sql = """
                select m.id, c.name as chat_name, u.username as sender, r.username as recipient,
                       m.body, m.created_at, m.deleted, m.status, m.edited,
                       m.reply_to_message_id as reply_to_id, reply_sender.username as reply_sender,
                       reply.body as reply_body, reply.deleted as reply_deleted
                from messages m
                join chats c on c.id = m.chat_id
                join users u on u.id = m.sender_id
                left join users r on r.id = m.recipient_id
                left join messages reply on reply.id = m.reply_to_message_id
                left join users reply_sender on reply_sender.id = reply.sender_id
                order by m.id desc
                limit ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, safeLimit);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<StoredMessage> messages = new ArrayList<>();
                while (resultSet.next()) {
                    messages.add(readMessage(resultSet));
                }
                return messages;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read recent messages", e);
        }
    }

    public synchronized void updateMessageStatus(long messageId, MessageStatus status) {
        try (PreparedStatement statement = connection.prepareStatement("update messages set status = ? where id = ?")) {
            statement.setString(1, status.name());
            statement.setLong(2, messageId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot update message status", e);
        }
    }

    public synchronized List<StoredMessage> markChatRead(String chatName, int readerId) {
        String chat = requireName(chatName, "chatName");
        String select = """
                select m.id
                from messages m
                join chats c on c.id = m.chat_id
                left join chat_members cm on cm.chat_id = c.id and cm.user_id = ?
                where c.name = ?
                  and m.sender_id <> ?
                  and m.deleted = 0
                  and m.status <> ?
                  and (c.type <> 'GROUP' or m.id > cm.joined_after_message_id)
                """;
        List<Long> messageIds = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(select)) {
            statement.setInt(1, readerId);
            statement.setString(2, chat);
            statement.setInt(3, readerId);
            statement.setString(4, MessageStatus.READ.name());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    messageIds.add(resultSet.getLong("id"));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot find unread messages", e);
        }
        if (messageIds.isEmpty()) {
            return List.of();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "update messages set status = ? where id = ?")) {
            for (long messageId : messageIds) {
                statement.setString(1, MessageStatus.READ.name());
                statement.setLong(2, messageId);
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot mark messages as read", e);
        }
        return messageIds.stream().map(this::historyById).toList();
    }

    public synchronized int unreadMessages(String chatName, int readerId) {
        String sql = """
                select count(*)
                from messages m
                join chats c on c.id = m.chat_id
                left join chat_members cm on cm.chat_id = c.id and cm.user_id = ?
                where c.name = ?
                  and m.sender_id <> ?
                  and m.deleted = 0
                  and m.status <> ?
                  and (c.type <> 'GROUP' or m.id > cm.joined_after_message_id)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, readerId);
            statement.setString(2, requireName(chatName, "chatName"));
            statement.setInt(3, readerId);
            statement.setString(4, MessageStatus.READ.name());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot count unread messages", e);
        }
    }

    public synchronized void deleteMessage(long messageId, ChatUser actor) {
        requireAdmin(actor);
        try (PreparedStatement statement = connection.prepareStatement("update messages set deleted = 1, body = '[deleted]' where id = ?")) {
            statement.setLong(1, messageId);
            if (statement.executeUpdate() == 0) {
                throw new IllegalArgumentException("Message not found: " + messageId);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot delete message", e);
        }
    }

    public synchronized StoredMessage editOwnMessage(long messageId, ChatUser actor, String newBody) {
        String sql = """
                update messages
                set body = ?, edited = 1
                where id = ?
                  and sender_id = ?
                  and deleted = 0
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, requireBody(newBody));
            statement.setLong(2, messageId);
            statement.setInt(3, actor.id());
            if (statement.executeUpdate() == 0) {
                throw new IllegalArgumentException("Only author can edit this message");
            }
            return historyById(messageId);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot edit message", e);
        }
    }

    public synchronized StoredMessage deleteOwnMessage(long messageId, ChatUser actor) {
        String sql = """
                update messages
                set deleted = 1, body = '[deleted]'
                where id = ?
                  and sender_id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, messageId);
            statement.setInt(2, actor.id());
            if (statement.executeUpdate() == 0) {
                throw new IllegalArgumentException("Only author can delete this message");
            }
            return historyById(messageId);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot delete own message", e);
        }
    }


    public synchronized StoredMessage setMessageReaction(long messageId, ChatUser actor, String reaction) {
        MessageTarget target = requireMessageVisible(messageId, actor.id());
        if (target.deleted()) {
            throw new IllegalArgumentException("Cannot react to deleted message");
        }
        String normalized = reaction == null ? "" : reaction.trim();
        try {
            String existing = null;
            try (PreparedStatement select = connection.prepareStatement(
                    "select reaction from message_reactions where message_id = ? and user_id = ?")) {
                select.setLong(1, messageId);
                select.setInt(2, actor.id());
                try (ResultSet resultSet = select.executeQuery()) {
                    if (resultSet.next()) {
                        existing = resultSet.getString("reaction");
                    }
                }
            }

            if (normalized.isBlank() || normalized.equals(existing)) {
                try (PreparedStatement delete = connection.prepareStatement(
                        "delete from message_reactions where message_id = ? and user_id = ?")) {
                    delete.setLong(1, messageId);
                    delete.setInt(2, actor.id());
                    delete.executeUpdate();
                }
            } else {
                normalized = normalizeReaction(normalized, null);
                try (PreparedStatement upsert = connection.prepareStatement("""
                        insert into message_reactions(message_id, user_id, reaction, created_at)
                        values (?, ?, ?, ?)
                        on conflict(message_id, user_id) do update set
                            reaction = excluded.reaction,
                            created_at = excluded.created_at
                        """)) {
                    upsert.setLong(1, messageId);
                    upsert.setInt(2, actor.id());
                    upsert.setString(3, normalized);
                    upsert.setString(4, Instant.now().toString());
                    upsert.executeUpdate();
                }
            }
            return historyById(messageId);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot update reaction", e);
        }
    }

    public synchronized List<String> messageRecipients(StoredMessage message) {
        if (message.recipient() != null) {
            return List.of(message.sender(), message.recipient());
        }
        return groupMembers(message.chatName());
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }

    private void init() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("pragma foreign_keys = on");
            statement.execute("""
                    create table if not exists users (
                        id integer primary key autoincrement,
                        username text not null unique,
                        password_hash text not null,
                        password_salt text,
                        role text not null,
                        blocked integer not null,
                        created_at text not null
                    )
                    """);
            statement.execute("""
                    create table if not exists chats (
                        id integer primary key autoincrement,
                        name text not null unique,
                        type text not null,
                        owner_id integer references users(id)
                    )
                    """);
            statement.execute("""
                    create table if not exists chat_members (
                        chat_id integer not null references chats(id),
                        user_id integer not null references users(id),
                        joined_at text not null,
                        joined_after_message_id integer not null default 0,
                        primary key (chat_id, user_id)
                    )
                    """);
            statement.execute("""
                    create table if not exists messages (
                        id integer primary key autoincrement,
                        chat_id integer not null references chats(id),
                        sender_id integer not null references users(id),
                        recipient_id integer references users(id),
                        body text not null,
                        created_at text not null,
                        deleted integer not null,
                        status text not null default 'SENT',
                        edited integer not null default 0,
                        reply_to_message_id integer references messages(id)
                    )
                    """);
            statement.execute("""
                    create table if not exists friendships (
                        user_id integer not null references users(id),
                        friend_id integer not null references users(id),
                        primary key (user_id, friend_id)
                    )
                    """);
            statement.execute("""
                    create table if not exists message_reactions (
                        message_id integer not null references messages(id) on delete cascade,
                        user_id integer not null references users(id) on delete cascade,
                        reaction text not null,
                        created_at text not null,
                        primary key (message_id, user_id)
                    )
                    """);
            ensureColumn(statement, "users", "password_salt", "text");
            ensureColumn(statement, "users", "description", "text not null default ''");
            ensureColumn(statement, "users", "avatar_data", "blob");
            ensureColumn(statement, "users", "avatar_content_type", "text");
            ensureColumn(statement, "users", "quick_reaction", "text not null default '❤️'");
            ensureColumn(statement, "chats", "owner_id", "integer references users(id)");
            ensureColumn(statement, "messages", "status", "text not null default 'SENT'");
            ensureColumn(statement, "messages", "edited", "integer not null default 0");
            ensureColumn(statement, "messages", "reply_to_message_id", "integer references messages(id)");
            ensureColumn(statement, "chat_members", "joined_at", "text");
            ensureColumn(statement, "chat_members", "joined_after_message_id", "integer not null default 0");
            statement.executeUpdate("update chat_members set joined_at = '1970-01-01T00:00:00Z' where joined_at is null");
            statement.executeUpdate("update chat_members set joined_after_message_id = 0 where joined_after_message_id is null");
            statement.executeUpdate("""
                    update chats
                    set owner_id = (
                        select min(cm.user_id)
                        from chat_members cm
                        where cm.chat_id = chats.id
                    )
                    where type = 'GROUP' and name <> 'general' and owner_id is null
                    """);
        }
        ensureChat(GENERAL_CHAT, "GROUP");
    }

    private <T> T inTransaction(Supplier<T> work) {
        boolean previousAutoCommit;
        try {
            previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot start database transaction", e);
        }

        try {
            T result = work.get();
            connection.commit();
            return result;
        } catch (RuntimeException | SQLException e) {
            rollbackQuietly();
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Cannot commit database transaction", e);
        } finally {
            try {
                connection.setAutoCommit(previousAutoCommit);
            } catch (SQLException e) {
                throw new IllegalStateException("Cannot restore auto-commit mode", e);
            }
        }
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
    }

    private void ensureColumn(Statement statement, String table, String column, String definition) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery("pragma table_info(" + table + ")")) {
            while (resultSet.next()) {
                if (column.equalsIgnoreCase(resultSet.getString("name"))) {
                    return;
                }
            }
        }
        statement.execute("alter table " + table + " add column " + column + " " + definition);
    }

    private long ensureChat(String chatName, String type) {
        try (PreparedStatement insert = connection.prepareStatement("insert or ignore into chats(name, type) values (?, ?)")) {
            insert.setString(1, chatName);
            insert.setString(2, type);
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot create chat", e);
        }

        try (PreparedStatement select = connection.prepareStatement("select id from chats where name = ?")) {
            select.setString(1, chatName);
            try (ResultSet resultSet = select.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getLong("id");
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read chat", e);
        }
        throw new IllegalStateException("Chat was not created: " + chatName);
    }

    private long createGroupRecord(String groupName, Integer ownerId) {
        String sql = "insert into chats(name, type, owner_id) values (?, 'GROUP', ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, groupName);
            if (ownerId == null) {
                statement.setNull(2, java.sql.Types.INTEGER);
            } else {
                statement.setInt(2, ownerId);
            }
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        } catch (SQLException e) {
            throw new IllegalArgumentException("Cannot create group: " + groupName, e);
        }
        throw new IllegalStateException("Group id was not generated");
    }

    private boolean chatExists(String chatName) {
        try (PreparedStatement statement = connection.prepareStatement("select 1 from chats where name = ?")) {
            statement.setString(1, chatName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot check chat", e);
        }
    }

    private long requireGroupId(String groupName) {
        String sql = "select id from chats where name = ? and type = 'GROUP'";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, groupName);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getLong("id");
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read group", e);
        }
        throw new IllegalArgumentException("Group not found: " + groupName);
    }

    private boolean hasMembership(long chatId, int userId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "select 1 from chat_members where chat_id = ? and user_id = ?")) {
            statement.setLong(1, chatId);
            statement.setInt(2, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot check group membership", e);
        }
    }

    private void requireGroupOwner(String groupName, int userId) {
        if (!isGroupOwner(groupName, userId)) {
            throw new IllegalArgumentException("Only the group creator can perform this action");
        }
    }

    private void deleteByChatId(String sql, long chatId) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, chatId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot delete group data", e);
        }
    }

    private List<Long> groupIdsOwnedBy(int userId) {
        String sql = """
                select id
                from chats
                where type = 'GROUP'
                  and owner_id = ?
                  and name <> ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, userId);
            statement.setString(2, GENERAL_CHAT);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<Long> ids = new ArrayList<>();
                while (resultSet.next()) {
                    ids.add(resultSet.getLong("id"));
                }
                return ids;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot find user groups", e);
        }
    }

    private void deleteMessagesForUser(int userId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "delete from messages where sender_id = ? or recipient_id = ?")) {
            statement.setInt(1, userId);
            statement.setInt(2, userId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot delete user messages", e);
        }
    }

    private void deleteFriendshipsForUser(int userId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "delete from friendships where user_id = ? or friend_id = ?")) {
            statement.setInt(1, userId);
            statement.setInt(2, userId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot delete user friendships", e);
        }
    }

    private void deleteMembershipsForUser(int userId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "delete from chat_members where user_id = ?")) {
            statement.setInt(1, userId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot delete user memberships", e);
        }
    }

    private void deletePrivateChat(String chatName) {
        String messageSql = "delete from messages where chat_id = (select id from chats where name = ? and type = 'PRIVATE')";
        try (PreparedStatement messages = connection.prepareStatement(messageSql);
             PreparedStatement chat = connection.prepareStatement("delete from chats where name = ? and type = 'PRIVATE'")) {
            messages.setString(1, chatName);
            messages.executeUpdate();
            chat.setString(1, chatName);
            chat.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot delete private chat", e);
        }
    }

    private void deleteEmptyPrivateChats() {
        String sql = """
                delete from chats
                where type = 'PRIVATE'
                  and not exists (select 1 from messages where messages.chat_id = chats.id)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot clean private chats", e);
        }
    }

    private void addFriendLink(int userId, int friendId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert or ignore into friendships(user_id, friend_id) values (?, ?)")) {
            statement.setInt(1, userId);
            statement.setInt(2, friendId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot add friend", e);
        }
    }

    private void removeFriendLink(int userId, int friendId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "delete from friendships where user_id = ? and friend_id = ?")) {
            statement.setInt(1, userId);
            statement.setInt(2, friendId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot remove friend", e);
        }
    }

    private void requireFriends(int firstUserId, int secondUserId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "select 1 from friendships where user_id = ? and friend_id = ?")) {
            statement.setInt(1, firstUserId);
            statement.setInt(2, secondUserId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot check friendship", e);
        }
        throw new IllegalArgumentException("Add this user as a friend before starting a private chat");
    }

    private StoredMessage saveMessage(String chatName, int senderId, Integer recipientId, String body, Long replyToMessageId) {
        long chatId = ensureChat(chatName, recipientId == null ? "GROUP" : "PRIVATE");
        validateReplyTarget(chatId, replyToMessageId);
        String sql = """
                insert into messages(chat_id, sender_id, recipient_id, body, created_at, deleted, status, reply_to_message_id)
                values (?, ?, ?, ?, ?, 0, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, chatId);
            statement.setInt(2, senderId);
            if (recipientId == null) {
                statement.setNull(3, java.sql.Types.INTEGER);
            } else {
                statement.setInt(3, recipientId);
            }
            statement.setString(4, body);
            statement.setString(5, Instant.now().toString());
            statement.setString(6, MessageStatus.SENT.name());
            if (replyToMessageId == null) {
                statement.setNull(7, java.sql.Types.INTEGER);
            } else {
                statement.setLong(7, replyToMessageId);
            }
            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return historyById(keys.getLong(1));
                }
            }
            throw new IllegalStateException("Message id was not generated");
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot save message", e);
        }
    }

    private StoredMessage historyById(long id) {
        String sql = """
                select m.id, c.name as chat_name, u.username as sender, r.username as recipient,
                       m.body, m.created_at, m.deleted, m.status, m.edited,
                       m.reply_to_message_id as reply_to_id, reply_sender.username as reply_sender,
                       reply.body as reply_body, reply.deleted as reply_deleted
                from messages m
                join chats c on c.id = m.chat_id
                join users u on u.id = m.sender_id
                left join users r on r.id = m.recipient_id
                left join messages reply on reply.id = m.reply_to_message_id
                left join users reply_sender on reply_sender.id = reply.sender_id
                where m.id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return readMessage(resultSet);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read message", e);
        }
        throw new IllegalStateException("Message not found after insert: " + id);
    }

    private void validateReplyTarget(long chatId, Long replyToMessageId) {
        if (replyToMessageId == null) {
            return;
        }
        if (replyToMessageId <= 0) {
            throw new IllegalArgumentException("replyTo must be a positive message id");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "select 1 from messages where id = ? and chat_id = ?")) {
            statement.setLong(1, replyToMessageId);
            statement.setLong(2, chatId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalArgumentException("Reply target does not belong to this chat");
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot validate reply target", e);
        }
    }

    private ChatUser findUserById(int userId) {
        try (PreparedStatement statement = connection.prepareStatement("select * from users where id = ?")) {
            statement.setInt(1, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return readUser(resultSet);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read updated profile", e);
        }
        throw new IllegalArgumentException("User not found");
    }

    private void changeBlocked(String username, ChatUser actor, boolean blocked) {
        requireAdmin(actor);
        String login = requireName(username, "username");
        if (actor.username().equals(login)) {
            throw new IllegalArgumentException("Admin cannot block himself");
        }
        try (PreparedStatement statement = connection.prepareStatement("update users set blocked = ? where username = ?")) {
            statement.setInt(1, blocked ? 1 : 0);
            statement.setString(2, login);
            if (statement.executeUpdate() == 0) {
                throw new IllegalArgumentException("User not found: " + login);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot update user block state", e);
        }
    }

    private static void requireAdmin(ChatUser actor) {
        if (actor.role() != UserRole.ADMIN) {
            throw new IllegalArgumentException("Only admin can perform this action");
        }
    }

    private static ChatUser readUser(ResultSet resultSet) throws SQLException {
        return new ChatUser(
                resultSet.getInt("id"),
                resultSet.getString("username"),
                UserRole.valueOf(resultSet.getString("role")),
                resultSet.getInt("blocked") != 0,
                Objects.requireNonNullElse(resultSet.getString("description"), ""),
                resultSet.getBytes("avatar_data") != null,
                normalizeReaction(resultSet.getString("quick_reaction"), DEFAULT_REACTION)
        );
    }

    private StoredMessage readMessage(ResultSet resultSet) throws SQLException {
        long replyToMessageId = resultSet.getLong("reply_to_id");
        Long replyTo = resultSet.wasNull() ? null : replyToMessageId;
        return new StoredMessage(
                resultSet.getLong("id"),
                resultSet.getString("chat_name"),
                resultSet.getString("sender"),
                resultSet.getString("recipient"),
                resultSet.getString("body"),
                Instant.parse(resultSet.getString("created_at")),
                resultSet.getInt("deleted") != 0,
                MessageStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("edited") != 0,
                replyTo,
                resultSet.getString("reply_sender"),
                resultSet.getString("reply_body"),
                resultSet.getInt("reply_deleted") != 0,
                reactionsForMessage(resultSet.getLong("id"))
        );
    }


    private List<StoredMessage.MessageReaction> reactionsForMessage(long messageId) {
        String sql = """
                select mr.reaction, u.username
                from message_reactions mr
                join users u on u.id = mr.user_id
                where mr.message_id = ?
                order by mr.created_at, u.username
                """;
        java.util.LinkedHashMap<String, List<String>> grouped = new java.util.LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, messageId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    grouped.computeIfAbsent(resultSet.getString("reaction"), ignored -> new ArrayList<>())
                            .add(resultSet.getString("username"));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read message reactions", e);
        }
        return grouped.entrySet().stream()
                .map(entry -> new StoredMessage.MessageReaction(entry.getKey(), entry.getValue().size(), List.copyOf(entry.getValue())))
                .toList();
    }

    private MessageTarget requireMessageVisible(long messageId, int userId) {
        String sql = """
                select m.id, m.deleted, c.id as chat_id, c.type, c.name, m.sender_id, m.recipient_id, cm.joined_after_message_id
                from messages m
                join chats c on c.id = m.chat_id
                left join chat_members cm on cm.chat_id = c.id and cm.user_id = ?
                where m.id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, userId);
            statement.setLong(2, messageId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalArgumentException("Message not found: " + messageId);
                }
                String type = resultSet.getString("type");
                int senderId = resultSet.getInt("sender_id");
                int recipientId = resultSet.getInt("recipient_id");
                boolean recipientNull = resultSet.wasNull();
                long joinedAfter = resultSet.getLong("joined_after_message_id");
                boolean hasMembership = !resultSet.wasNull();
                boolean visible = "PRIVATE".equals(type)
                        ? senderId == userId || (!recipientNull && recipientId == userId)
                        : hasMembership && messageId > joinedAfter;
                if (!visible) {
                    throw new IllegalArgumentException("Message is not available");
                }
                return new MessageTarget(resultSet.getInt("deleted") != 0);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot validate message access", e);
        }
    }

    private record MessageTarget(boolean deleted) {
    }

    private static String privateChatName(int firstUserId, int secondUserId) {
        int min = Math.min(firstUserId, secondUserId);
        int max = Math.max(firstUserId, secondUserId);
        return "private-" + min + "-" + max;
    }

    private static String requireName(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }


    private static String normalizeReaction(String value, String fallback) {
        String reaction = value == null ? "" : value.trim();
        if (reaction.isBlank()) {
            if (fallback != null) {
                return fallback;
            }
            throw new IllegalArgumentException("Reaction is required");
        }
        if (!ALLOWED_REACTIONS.contains(reaction)) {
            throw new IllegalArgumentException("Unsupported reaction");
        }
        return reaction;
    }

    private static String cleanDescription(String value) {
        String description = Objects.requireNonNullElse(value, "").trim();
        if (description.length() > 280) {
            throw new IllegalArgumentException("Profile description is too long");
        }
        return description;
    }

    private static String requireBody(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("message body is required");
        }
        return value.trim();
    }

    private String createSalt() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String passwordHash(String username, String password, String salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(
                    (username + ":" + password).toCharArray(),
                    Base64.getDecoder().decode(salt),
                    PBKDF2_ITERATIONS,
                    PBKDF2_BITS
            );
            byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot hash password", e);
        }
    }

    private static String legacyPasswordHash(String username, String password) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((username + ":" + password).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new BigInteger(1, hash).toString(16);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot hash password", e);
        }
    }
}
