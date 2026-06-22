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
import java.util.function.Supplier;

public final class ChatDatabase implements AutoCloseable {
    private static final String GENERAL_CHAT = "general";
    private static final int PBKDF2_ITERATIONS = 120_000;
    private static final int PBKDF2_BITS = 256;

    private final Connection connection;
    private final SecureRandom random = new SecureRandom();

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
                        ChatUser user = new ChatUser(keys.getInt(1), login, role, false);
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

    public synchronized void blockUser(String username, ChatUser actor) {
        changeBlocked(username, actor, true);
    }

    public synchronized void unblockUser(String username, ChatUser actor) {
        changeBlocked(username, actor, false);
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
        String sql = "insert or ignore into chat_members(chat_id, user_id) values (?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, chatId);
            statement.setInt(2, userId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot join group", e);
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
        String chat = requireName(groupName, "groupName");
        String messageBody = requireBody(body);
        return inTransaction(() -> {
            requireGroupMembership(chat, senderId);
            return saveMessage(chat, senderId, null, messageBody);
        });
    }

    public synchronized StoredMessage savePrivateMessage(int senderId, String recipientUsername, String body) {
        String messageBody = requireBody(body);
        return inTransaction(() -> {
            ChatUser recipient = findUser(recipientUsername)
                    .orElseThrow(() -> new IllegalArgumentException("Recipient not found: " + recipientUsername));
            requireFriends(senderId, recipient.id());
            String chatName = privateChatName(senderId, recipient.id());
            ensureChat(chatName, "PRIVATE");
            return saveMessage(chatName, senderId, recipient.id(), messageBody);
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
            return null;
        });
    }

    public synchronized List<StoredMessage> history(String chatName, int limit) {
        String chat = requireName(chatName, "chatName");
        int safeLimit = Math.max(1, Math.min(limit, 100));
        String sql = """
                select m.id, c.name as chat_name, u.username as sender, r.username as recipient,
                       m.body, m.created_at, m.deleted, m.status, m.edited
                from messages m
                join chats c on c.id = m.chat_id
                join users u on u.id = m.sender_id
                left join users r on r.id = m.recipient_id
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

    public synchronized List<StoredMessage> recentMessages(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        String sql = """
                select m.id, c.name as chat_name, u.username as sender, r.username as recipient,
                       m.body, m.created_at, m.deleted, m.status, m.edited
                from messages m
                join chats c on c.id = m.chat_id
                join users u on u.id = m.sender_id
                left join users r on r.id = m.recipient_id
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

    public synchronized void markChatRead(String chatName, int readerId) {
        String sql = """
                update messages
                set status = ?
                where chat_id = (select id from chats where name = ?)
                  and sender_id <> ?
                  and deleted = 0
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, MessageStatus.READ.name());
            statement.setString(2, requireName(chatName, "chatName"));
            statement.setInt(3, readerId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot mark messages as read", e);
        }
    }

    public synchronized int unreadMessages(String chatName, int readerId) {
        String sql = """
                select count(*)
                from messages m
                join chats c on c.id = m.chat_id
                where c.name = ?
                  and m.sender_id <> ?
                  and m.deleted = 0
                  and m.status <> ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, requireName(chatName, "chatName"));
            statement.setInt(2, readerId);
            statement.setString(3, MessageStatus.READ.name());
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
                        edited integer not null default 0
                    )
                    """);
            statement.execute("""
                    create table if not exists friendships (
                        user_id integer not null references users(id),
                        friend_id integer not null references users(id),
                        primary key (user_id, friend_id)
                    )
                    """);
            ensureColumn(statement, "users", "password_salt", "text");
            ensureColumn(statement, "chats", "owner_id", "integer references users(id)");
            ensureColumn(statement, "messages", "status", "text not null default 'SENT'");
            ensureColumn(statement, "messages", "edited", "integer not null default 0");
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

    private StoredMessage saveMessage(String chatName, int senderId, Integer recipientId, String body) {
        long chatId = ensureChat(chatName, recipientId == null ? "GROUP" : "PRIVATE");
        String sql = """
                insert into messages(chat_id, sender_id, recipient_id, body, created_at, deleted, status)
                values (?, ?, ?, ?, ?, 0, ?)
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
                       m.body, m.created_at, m.deleted, m.status, m.edited
                from messages m
                join chats c on c.id = m.chat_id
                join users u on u.id = m.sender_id
                left join users r on r.id = m.recipient_id
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
                resultSet.getInt("blocked") != 0
        );
    }

    private static StoredMessage readMessage(ResultSet resultSet) throws SQLException {
        return new StoredMessage(
                resultSet.getLong("id"),
                resultSet.getString("chat_name"),
                resultSet.getString("sender"),
                resultSet.getString("recipient"),
                resultSet.getString("body"),
                Instant.parse(resultSet.getString("created_at")),
                resultSet.getInt("deleted") != 0,
                MessageStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("edited") != 0
        );
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
