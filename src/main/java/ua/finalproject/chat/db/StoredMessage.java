package ua.finalproject.chat.db;

import java.time.Instant;

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
        boolean replyDeleted
) {
}
