package ua.finalproject.chat.db;

import java.time.Instant;
import java.util.List;

public record StoredMessage(
        long id,
        String chatName,
        String sender,
        String recipient,
        String body,
        Instant createdAt,
        boolean deleted,
        MessageStatus status,
        boolean edited,
        Long replyToMessageId,
        String replySender,
        String replyBody,
        boolean replyDeleted,
        List<MessageReaction> reactions
) {
    public record MessageReaction(String reaction, int count, List<String> users) {
    }
}
