package ua.finalproject.chat;

import org.junit.jupiter.api.Test;
import ua.finalproject.chat.db.ChatDatabase;
import ua.finalproject.chat.db.ChatUser;
import ua.finalproject.chat.db.MessageStatus;
import ua.finalproject.chat.db.StoredMessage;
import ua.finalproject.chat.db.UserRole;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatDatabaseTest {
    @Test
    void registersAuthenticatesAndStoresHistory() throws Exception {
        try (ChatDatabase database = new ChatDatabase("jdbc:sqlite::memory:")) {
            ChatUser alice = database.register("alice", "pass");
            ChatUser bob = database.register("bob", "pass");

            assertEquals(alice, database.authenticate("alice", "pass"));
            assertEquals(List.of("alice", "bob"), database.listUsers());

            assertThrows(IllegalArgumentException.class, () -> database.savePrivateMessage(alice.id(), "bob", "hello"));
            database.addFriend(alice.id(), "bob");
            StoredMessage message = database.savePrivateMessage(alice.id(), "bob", "hello");
            List<StoredMessage> history = database.history(message.chatName(), 10);

            assertEquals(1, history.size());
            assertEquals("alice", history.get(0).sender());
            assertEquals("bob", history.get(0).recipient());
            assertEquals("hello", history.get(0).body());
            assertEquals(MessageStatus.SENT, history.get(0).status());
            assertFalse(history.get(0).deleted());

            List<StoredMessage> readMessages = database.markChatRead(message.chatName(), bob.id());
            assertEquals(1, readMessages.size());
            assertEquals(MessageStatus.READ, readMessages.get(0).status());
            assertTrue(database.markChatRead(message.chatName(), bob.id()).isEmpty());
            assertEquals(MessageStatus.READ, database.history(message.chatName(), 10).get(0).status());
        }
    }

    @Test
    void adminCanDeleteMessage() throws Exception {
        try (ChatDatabase database = new ChatDatabase("jdbc:sqlite::memory:")) {
            ChatUser admin = database.register("admin", "admin");
            ChatUser user = database.register("user", "pass");
            StoredMessage message = database.savePublicMessage(user.id(), "general", "temporary");

            assertEquals(UserRole.ADMIN, admin.role());
            database.deleteMessage(message.id(), admin);

            List<StoredMessage> history = database.history("general", 10);
            assertTrue(history.get(0).deleted());
            assertEquals("[deleted]", history.get(0).body());
        }
    }

    @Test
    void adminCanBlockAndUnblockUser() throws Exception {
        try (ChatDatabase database = new ChatDatabase("jdbc:sqlite::memory:")) {
            ChatUser admin = database.register("admin", "admin");
            database.register("user", "pass");

            database.blockUser("user", admin);
            assertTrue(database.findUser("user").orElseThrow().blocked());

            database.unblockUser("user", admin);
            assertFalse(database.findUser("user").orElseThrow().blocked());
        }
    }

    @Test
    void groupsRequireExistingNameTrackOwnerAndSupportLeaving() throws Exception {
        try (ChatDatabase database = new ChatDatabase("jdbc:sqlite::memory:")) {
            ChatUser alice = database.register("alice", "pass");
            ChatUser bob = database.register("bob", "pass");

            assertThrows(IllegalArgumentException.class, () -> database.joinGroup(alice.id(), "missing"));

            database.createGroupForUser("team", alice.id());
            database.addGroupMember("team", "bob", alice);

            assertEquals(List.of("alice", "bob"), database.groupMembers("team"));
            assertEquals(List.of("team"), database.groupsForUser(alice.id()));
            assertTrue(database.isGroupOwner("team", alice.id()));
            assertFalse(database.isGroupOwner("team", bob.id()));

            database.leaveGroup("team", bob);
            assertEquals(List.of("alice"), database.groupMembers("team"));
            assertThrows(IllegalArgumentException.class, () -> database.leaveGroup("team", alice));

            database.deleteGroup("team", alice);
            assertTrue(database.groupsForUser(alice.id()).isEmpty());
        }
    }

    @Test
    void groupCreationRollsBackWhenOwnerCannotBeAdded() throws Exception {
        try (ChatDatabase database = new ChatDatabase("jdbc:sqlite::memory:")) {
            ChatUser alice = database.register("alice", "pass");

            assertThrows(IllegalArgumentException.class, () -> database.createGroupForUser("broken-group", -1));

            assertFalse(database.groupsForUser(alice.id()).contains("broken-group"));
        }
    }

    @Test
    void authorCanEditAndDeleteOwnMessage() throws Exception {
        try (ChatDatabase database = new ChatDatabase("jdbc:sqlite::memory:")) {
            ChatUser alice = database.register("alice", "pass");
            database.register("bob", "pass");
            database.addFriend(alice.id(), "bob");
            StoredMessage message = database.savePrivateMessage(alice.id(), "bob", "first");

            StoredMessage edited = database.editOwnMessage(message.id(), alice, "second");

            assertEquals("second", edited.body());
            assertTrue(edited.edited());

            StoredMessage deleted = database.deleteOwnMessage(message.id(), alice);

            assertEquals("[deleted]", deleted.body());
            assertTrue(deleted.deleted());
        }
    }

    @Test
    void deletingFriendRemovesPrivateChatHistoryBeforeAnotherConversation() throws Exception {
        try (ChatDatabase database = new ChatDatabase("jdbc:sqlite::memory:")) {
            ChatUser alice = database.register("alice", "pass");
            ChatUser bob = database.register("bob", "pass");
            database.addFriend(alice.id(), "bob");
            String chat = database.savePrivateMessage(alice.id(), "bob", "first chat").chatName();

            database.removeFriend(alice.id(), "bob");
            assertTrue(database.history(chat, 10).isEmpty());
            assertTrue(database.friendsForUser(alice.id()).isEmpty());
            assertTrue(database.friendsForUser(bob.id()).isEmpty());

            database.addFriend(alice.id(), "bob");
            database.savePrivateMessage(alice.id(), "bob", "new chat");
            List<StoredMessage> history = database.history(chat, 10);
            assertEquals(1, history.size());
            assertEquals("new chat", history.get(0).body());
        }
    }

    @Test
    void adminPermanentlyDeletesUserWithMessagesFriendshipsAndOwnedGroups() throws Exception {
        try (ChatDatabase database = new ChatDatabase("jdbc:sqlite::memory:")) {
            ChatUser admin = database.register("admin", "admin");
            ChatUser alice = database.register("alice", "pass");
            ChatUser bob = database.register("bob", "pass");
            database.addFriend(alice.id(), "bob");
            String privateChat = database.savePrivateMessage(alice.id(), "bob", "private message").chatName();
            database.createGroupForUser("alice-team", alice.id());
            database.addGroupMember("alice-team", "bob", alice);
            database.savePublicMessage(alice.id(), "alice-team", "group message");

            database.deleteUserPermanently("alice", admin);

            assertTrue(database.findUser("alice").isEmpty());
            assertTrue(database.friendsForUser(bob.id()).isEmpty());
            assertTrue(database.groupsForUser(bob.id()).isEmpty());
            assertTrue(database.history(privateChat, 10).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> database.joinGroup(bob.id(), "alice-team"));
            assertThrows(IllegalArgumentException.class, () -> database.deleteUserPermanently("admin", admin));
        }
    }

    @Test
    void groupHistoryStartsFromTheLatestMembership() throws Exception {
        try (ChatDatabase database = new ChatDatabase("jdbc:sqlite::memory:")) {
            ChatUser alice = database.register("alice", "pass");
            ChatUser bob = database.register("bob", "pass");
            database.createGroupForUser("team", alice.id());
            database.savePublicMessage(alice.id(), "team", "before joining");

            database.addGroupMember("team", "bob", alice);
            database.savePublicMessage(alice.id(), "team", "after first join");
            assertEquals(List.of("after first join"), database.historyForUser("team", bob.id(), 10).stream()
                    .map(StoredMessage::body)
                    .toList());

            database.removeGroupMember("team", "bob", alice);
            database.savePublicMessage(alice.id(), "team", "while away");
            database.addGroupMember("team", "bob", alice);
            database.savePublicMessage(alice.id(), "team", "after returning");

            assertEquals(List.of("after returning"), database.historyForUser("team", bob.id(), 10).stream()
                    .map(StoredMessage::body)
                    .toList());
        }
    }
}
