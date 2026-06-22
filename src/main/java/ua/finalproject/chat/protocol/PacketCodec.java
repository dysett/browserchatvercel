package ua.finalproject.chat.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;

public final class PacketCodec {
    public static final int MAGIC = 0x13;

    private static final int HEADER_WITHOUT_CRC_LENGTH = 14;
    private static final int HEADER_LENGTH = 16;
    private static final int BODY_CRC_LENGTH = 2;
    private static final int MIN_PACKET_LENGTH = HEADER_LENGTH + BODY_CRC_LENGTH;

    private final AesMessageCipher cipher;

    public PacketCodec(AesMessageCipher cipher) {
        this.cipher = Objects.requireNonNull(cipher, "cipher");
    }

    public byte[] encode(Packet packet) {
        Objects.requireNonNull(packet, "packet");

        byte[] plainMessage = MessageCodec.encode(packet.message());
        byte[] encryptedMessage = cipher.encrypt(plainMessage);

        ByteBuffer buffer = ByteBuffer.allocate(HEADER_LENGTH + encryptedMessage.length + BODY_CRC_LENGTH)
                .order(ByteOrder.BIG_ENDIAN);
        buffer.put((byte) MAGIC);
        buffer.put((byte) packet.source());
        buffer.putLong(packet.packetId());
        buffer.putInt(encryptedMessage.length);

        byte[] bytes = buffer.array();
        buffer.putShort((short) Crc16.calculate(bytes, 0, HEADER_WITHOUT_CRC_LENGTH));
        buffer.put(encryptedMessage);
        buffer.putShort((short) Crc16.calculate(encryptedMessage));
        return bytes;
    }

    public Packet decode(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length < MIN_PACKET_LENGTH) {
            throw new ProtocolException("Packet is too short");
        }

        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        int magic = Byte.toUnsignedInt(buffer.get());
        if (magic != MAGIC) {
            throw new ProtocolException("Invalid magic byte");
        }

        int source = Byte.toUnsignedInt(buffer.get());
        long packetId = buffer.getLong();
        int length = buffer.getInt();
        if (length < 0) {
            throw new ProtocolException("Negative message length");
        }

        int expectedLength = HEADER_LENGTH + length + BODY_CRC_LENGTH;
        if (bytes.length != expectedLength) {
            throw new ProtocolException("Packet length does not match header length");
        }

        int headerCrc = Short.toUnsignedInt(buffer.getShort());
        int expectedHeaderCrc = Crc16.calculate(bytes, 0, HEADER_WITHOUT_CRC_LENGTH);
        if (headerCrc != expectedHeaderCrc) {
            throw new ProtocolException("Header CRC mismatch");
        }

        byte[] encryptedMessage = Arrays.copyOfRange(bytes, HEADER_LENGTH, HEADER_LENGTH + length);
        int bodyCrc = Short.toUnsignedInt(buffer.getShort(HEADER_LENGTH + length));
        int expectedBodyCrc = Crc16.calculate(encryptedMessage);
        if (bodyCrc != expectedBodyCrc) {
            throw new ProtocolException("Body CRC mismatch");
        }

        return new Packet(source, packetId, MessageCodec.decode(cipher.decrypt(encryptedMessage)));
    }
}
