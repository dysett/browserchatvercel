package ua.finalproject.chat.protocol;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record ChatMessage(ChatCommand command, int userId, Map<String, String> fields) {
    public ChatMessage {
        Objects.requireNonNull(command, "command");
        fields = Map.copyOf(Objects.requireNonNull(fields, "fields"));
    }

    public static ChatMessage of(ChatCommand command, int userId, Map<String, String> fields) {
        return new ChatMessage(command, userId, fields);
    }

    public String field(String name) {
        return fields.get(name);
    }

    public String requiredField(String name) {
        String value = fields.get(name);
        if (value == null || value.isBlank()) {
            throw new ProtocolException("Missing field: " + name);
        }
        return value;
    }

    public static Map<String, String> fields(String... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("Fields must be key-value pairs");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                result.put(keyValues[i], keyValues[i + 1]);
            }
        }
        return result;
    }
}
