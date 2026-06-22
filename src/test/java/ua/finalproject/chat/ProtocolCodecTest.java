package ua.finalproject.chat;

import org.junit.jupiter.api.Test;
import ua.finalproject.chat.protocol.AesMessageCipher;
import ua.finalproject.chat.protocol.ChatCommand;
import ua.finalproject.chat.protocol.ChatMessage;
import ua.finalproject.chat.protocol.Packet;
import ua.finalproject.chat.protocol.PacketCodec;
import ua.finalproject.chat.protocol.ProtocolException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProtocolCodecTest {
    @Test
    void encodesEncryptsAndDecodesPacket() {
        PacketCodec codec = new PacketCodec(new AesMessageCipher("test-secret"));
        Packet packet = new Packet(7, 42, ChatMessage.of(
                ChatCommand.SEND_PRIVATE,
                10,
                Map.of("to", "bob", "text", "hello")
        ));

        Packet decoded = codec.decode(codec.encode(packet));

        assertEquals(packet.source(), decoded.source());
        assertEquals(packet.packetId(), decoded.packetId());
        assertEquals(packet.message(), decoded.message());
    }

    @Test
    void rejectsCorruptedPacketByCrc() {
        PacketCodec codec = new PacketCodec(new AesMessageCipher("test-secret"));
        byte[] bytes = codec.encode(new Packet(1, 1, ChatMessage.of(
                ChatCommand.USERS,
                1,
                Map.of()
        )));

        bytes[20] ^= 1;

        assertThrows(ProtocolException.class, () -> codec.decode(bytes));
    }
}
