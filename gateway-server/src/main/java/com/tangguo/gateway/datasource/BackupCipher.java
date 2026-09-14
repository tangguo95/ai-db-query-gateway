package com.tangguo.gateway.datasource;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** 可跨设备解密的版本化备份格式；头部也参与认证，文件中不保存口令或明文连接信息。 */
public final class BackupCipher {
    private static final byte[] MAGIC = "GWAYBK01".getBytes(StandardCharsets.US_ASCII);
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    private BackupCipher() {}

    public static byte[] encrypt(byte[] plain, String password) throws GeneralSecurityException {
        if (plain.length > MAX_BYTES - 52) throw new GeneralSecurityException("backup too large");
        byte[] salt = new byte[16], iv = new byte[12];
        var random = new SecureRandom();
        random.nextBytes(salt);
        random.nextBytes(iv);
        byte[] header = ByteBuffer.allocate(36).put(MAGIC).put(salt).put(iv).array();
        byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, plain, password, salt, iv, header);
        return ByteBuffer.allocate(header.length + encrypted.length).put(header).put(encrypted).array();
    }

    public static byte[] decrypt(byte[] file, String password) throws GeneralSecurityException {
        if (file.length < 52 || file.length > MAX_BYTES
                || !Arrays.equals(Arrays.copyOf(file, 8), MAGIC)) {
            throw new GeneralSecurityException("invalid backup");
        }
        return crypt(Cipher.DECRYPT_MODE, Arrays.copyOfRange(file, 36, file.length), password,
                Arrays.copyOfRange(file, 8, 24), Arrays.copyOfRange(file, 24, 36), Arrays.copyOf(file, 36));
    }

    private static byte[] crypt(int mode, byte[] data, String password, byte[] salt, byte[] iv, byte[] header)
            throws GeneralSecurityException {
        if (password == null || password.length() < 12 || password.length() > 256) {
            throw new GeneralSecurityException("invalid password length");
        }
        char[] chars = password.toCharArray();
        var spec = new PBEKeySpec(chars, salt, 600_000, 256);
        byte[] key = null;
        try {
            key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(header);
            return cipher.doFinal(data);
        } finally {
            spec.clearPassword();
            Arrays.fill(chars, '\0');
            if (key != null) Arrays.fill(key, (byte) 0);
        }
    }
}
