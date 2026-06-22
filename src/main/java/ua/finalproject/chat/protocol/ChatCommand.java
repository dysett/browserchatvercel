package ua.finalproject.chat.protocol;

import java.util.Arrays;

public enum ChatCommand {
    REGISTER(1),
    LOGIN(2),
    SEND_PUBLIC(3),
    SEND_PRIVATE(4),
    HISTORY(5),
    USERS(6),
    CREATE_GROUP(7),
    JOIN_GROUP(8),
    SEND_GROUP(9),
    ADMIN_DELETE_MESSAGE(10),
    LOGOUT(11),
    ADMIN_BLOCK_USER(12),
    ADMIN_UNBLOCK_USER(13),
    MARK_READ(14),
    TYPING(15),
    ADD_GROUP_MEMBER(16),
    REMOVE_GROUP_MEMBER(17),
    EDIT_MESSAGE(18),
    DELETE_OWN_MESSAGE(19),
    LEAVE_GROUP(20),
    DELETE_GROUP(21),
    ADD_FRIEND(22),
    REMOVE_FRIEND(23),
    ADMIN_DELETE_USER(24),
    RESPONSE(1000),
    EVENT_MESSAGE(1001),
    EVENT_USERS(1002),
    EVENT_STATUS(1003),
    EVENT_TYPING(1004),
    EVENT_MESSAGE_UPDATE(1005);

    private final int code;

    ChatCommand(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static ChatCommand fromCode(int code) {
        return Arrays.stream(values())
                .filter(command -> command.code == code)
                .findFirst()
                .orElseThrow(() -> new ProtocolException("Unknown command code: " + code));
    }
}
