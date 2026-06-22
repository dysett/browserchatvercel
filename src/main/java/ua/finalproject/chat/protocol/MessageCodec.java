package ua.finalproject.chat.protocol;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class MessageCodec {
    private static final int HEADER_LENGTH = 8;

    private MessageCodec() {
    }

    public static byte[] encode(ChatMessage message) {
        Objects.requireNonNull(message, "message");
        StringBuilder payload = new StringBuilder();
        for (Map.Entry<String, String> entry : message.fields().entrySet()) {
            payload.append(escape(entry.getKey()))
                    .append('=')
                    .append(escape(entry.getValue()))
                    .append('\n');
        }

        byte[] payloadBytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(HEADER_LENGTH + payloadBytes.length)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(message.command().code())
                .putInt(message.userId())
                .put(payloadBytes)
                .array();
    }

    public static ChatMessage decode(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length < HEADER_LENGTH) {
            throw new ProtocolException("Message is shorter than header");
        }

        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        ChatCommand command = ChatCommand.fromCode(buffer.getInt());
        int userId = buffer.getInt();
        byte[] payloadBytes = new byte[buffer.remaining()];
        buffer.get(payloadBytes);

        Map<String, String> fields = new LinkedHashMap<>();
        String payload = new String(payloadBytes, StandardCharsets.UTF_8);
        if (!payload.isBlank()) {
            for (String line : payload.split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                int separator = line.indexOf('=');
                if (separator <= 0) {
                    throw new ProtocolException("Invalid payload line");
                }
                fields.put(unescape(line.substring(0, separator)), unescape(line.substring(separator + 1)));
            }
        }
        return new ChatMessage(command, userId, fields);
    }

    private static String escape(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String unescape(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
