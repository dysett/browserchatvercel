package ua.finalproject.chat.protocol;

public final class Crc16 {
    private static final int POLYNOMIAL = 0x1021;
    private static final int INITIAL_VALUE = 0xFFFF;

    private Crc16() {
    }

    public static int calculate(byte[] bytes) {
        return calculate(bytes, 0, bytes.length);
    }

    public static int calculate(byte[] bytes, int offset, int length) {
        int crc = INITIAL_VALUE;
        for (int index = offset; index < offset + length; index++) {
            crc ^= (bytes[index] & 0xFF) << 8;
            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 0x8000) != 0) {
                    crc = (crc << 1) ^ POLYNOMIAL;
                } else {
                    crc <<= 1;
                }
                crc &= 0xFFFF;
            }
        }
        return crc & 0xFFFF;
    }
}
