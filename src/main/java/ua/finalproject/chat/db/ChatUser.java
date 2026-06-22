package ua.finalproject.chat.db;

public record ChatUser(int id, String username, UserRole role, boolean blocked) {
}
