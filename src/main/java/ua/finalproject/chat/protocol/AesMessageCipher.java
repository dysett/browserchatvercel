package ua.finalproject.chat.protocol;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

public final class AesMessageCipher {
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public AesMessageCipher(String sharedSecret) {
        Objects.requireNonNull(sharedSecret, "sharedSecret");
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(sharedSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(Arrays.copyOf(hash, 16), "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create AES key", e);
        }
    }

    public byte[] encrypt(byte[] plain) {
        Objects.requireNonNull(plain, "plain");
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain);

            byte[] result = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, result, 0, iv.length);
            System.arraycopy(encrypted, 0, result, iv.length, encrypted.length);
            return result;
        } catch (Exception e) {
            throw new ProtocolException("Cannot encrypt message", e);
        }
    }

    public byte[] decrypt(byte[] encryptedWithIv) {
        Objects.requireNonNull(encryptedWithIv, "encryptedWithIv");
        if (encryptedWithIv.length <= IV_LENGTH) {
            throw new ProtocolException("Encrypted message is too short");
        }

        try {
            byte[] iv = Arrays.copyOfRange(encryptedWithIv, 0, IV_LENGTH);
            byte[] encrypted = Arrays.copyOfRange(encryptedWithIv, IV_LENGTH, encryptedWithIv.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(encrypted);
        } catch (Exception e) {
            throw new ProtocolException("Cannot decrypt message", e);
        }
    }
}
