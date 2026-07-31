package com.tangguo.gateway.audit;

import com.tangguo.gateway.api.GatewayException;
import com.tangguo.gateway.secret.SecretStore;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AuditCryptoService {
    private static final String AES_ACCOUNT = "gateway.audit.aes.v1";
    private static final String HMAC_ACCOUNT = "gateway.audit.hmac.v1";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretStore secretStore;
    private byte[] aesKey;
    private byte[] hmacKey;

    public AuditCryptoService(SecretStore secretStore) {
        this.secretStore = secretStore;
    }

    @PostConstruct
    synchronized void initializeKeys() {
        aesKey = loadOrCreate(AES_ACCOUNT);
        hmacKey = loadOrCreate(HMAC_ACCOUNT);
    }

    public String encrypt(String plainText) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return "v1." + Base64.getUrlEncoder().withoutPadding().encodeToString(iv)
                    + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted);
        } catch (GeneralSecurityException exception) {
            throw cryptoFailure(exception);
        }
    }

    public String decrypt(String encoded) {
        try {
            String[] parts = encoded.split("\\.", 3);
            if (parts.length != 3 || !"v1".equals(parts[0])) {
                throw new GeneralSecurityException("unsupported ciphertext");
            }
            byte[] iv = Base64.getUrlDecoder().decode(parts[1]);
            byte[] encrypted = Base64.getUrlDecoder().decode(parts[2]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw cryptoFailure(exception);
        }
    }

    public String hmac(String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw cryptoFailure(exception);
        }
    }

    public boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII));
    }

    private byte[] loadOrCreate(String account) {
        return secretStore.get(account)
                .map(value -> Base64.getDecoder().decode(value))
                .orElseGet(() -> {
                    byte[] key = new byte[32];
                    RANDOM.nextBytes(key);
                    secretStore.put(account, Base64.getEncoder().encodeToString(key));
                    return key;
                });
    }

    private GatewayException cryptoFailure(Exception exception) {
        return new GatewayException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "AUDIT_CRYPTO_UNAVAILABLE",
                "审计加密服务不可用",
                exception);
    }
}
