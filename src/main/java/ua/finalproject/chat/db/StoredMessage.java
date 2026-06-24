package ua.finalproject.chat.db;

import java.time.Instant;
import java.util.List;

/**
 * Повідомлення, прочитане з бази даних.
 * Об'єкт містить основний текст, службові поля, дані для відповіді та список реакцій.
 */
public record StoredMessage(
        long id,
        String chatName,
        String sender,
        String recipient,
        String body,
        Instant createdAt,
        boolean deleted,
        boolean system,
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
