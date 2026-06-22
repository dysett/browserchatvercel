package ua.finalproject.chat.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class PacketIo {
    private static final int MAX_PACKET_BYTES = 1024 * 1024;

    private final DataInputStream input;
    private final DataOutputStream output;

    public PacketIo(InputStream input, OutputStream output) {
        this.input = new DataInputStream(input);
        this.output = new DataOutputStream(output);
    }

    public byte[] readPacketBytes() throws IOException {
        int length;
        try {
            length = input.readInt();
        } catch (EOFException e) {
            return null;
        }
        if (length <= 0 || length > MAX_PACKET_BYTES) {
            throw new ProtocolException("Invalid TCP frame length: " + length);
        }
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return bytes;
    }

    public synchronized void writePacketBytes(byte[] bytes) throws IOException {
        output.writeInt(bytes.length);
        output.write(bytes);
        output.flush();
    }
}
