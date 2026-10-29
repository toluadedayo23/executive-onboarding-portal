package ng.risktech.execplatform.onboarding;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

public class FieldEncryptionService {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public FieldEncryptionService(String encodedKey) {
        try {
            byte[] bytes = Base64.getDecoder().decode(encodedKey);
            if (bytes.length != 32) throw new IllegalArgumentException("ENCRYPTION_KEY must encode exactly 32 bytes");
            key = new SecretKeySpec(bytes, "AES");
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("ENCRYPTION_KEY is invalid", ex);
        }
    }

    public String encrypt(String plaintext, String aad) {
        try {
            byte[] iv = new byte[12]; random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return "v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(combined);
        } catch (Exception ex) { throw new IllegalStateException("Unable to protect field", ex); }
    }

    public String decrypt(String value, String aad) {
        try {
            if (value == null || !value.startsWith("v1:")) throw new IllegalArgumentException();
            byte[] combined = Base64.getUrlDecoder().decode(value.substring(3));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, combined, 0, 12));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(combined, 12, combined.length - 12), StandardCharsets.UTF_8);
        } catch (Exception ex) { throw new IllegalStateException("Unable to read protected field", ex); }
    }
}
