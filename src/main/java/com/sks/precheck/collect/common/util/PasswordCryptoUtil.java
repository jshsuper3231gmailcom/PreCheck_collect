package com.sks.precheck.collect.common.util;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * PreCheck_CollectServer_Auth.conf에 저장하는 비밀번호를 AES-256-GCM으로 암/복호화하는 유틸.
 *
 * 마스터 키는 환경변수 COLLECT_AUTH_SECRET_KEY(Base64 인코딩된 32byte 키)에서 읽는다.
 * conf 파일에는 평문 대신 ENC(Base64(IV||암호문||태그)) 형태로 저장한다.
 * 신규 서버 추가 시에도 마스터 키는 그대로 두고, 비밀번호만 이 유틸(encrypt)로 암호화해서
 * conf에 추가하면 된다 — 키 재생성은 전체 서버 재암호화가 필요하므로 회전 시에만 사용한다.
 */
public final class PasswordCryptoUtil {

    private static final String ENV_KEY_NAME = "COLLECT_AUTH_SECRET_KEY";
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final String ENC_PREFIX = "ENC(";
    private static final String ENC_SUFFIX = ")";

    private PasswordCryptoUtil() {
    }

    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(ENC_PREFIX) && value.endsWith(ENC_SUFFIX);
    }

    public static String unwrapEnc(String value) {
        return value.substring(ENC_PREFIX.length(), value.length() - ENC_SUFFIX.length());
    }

    public static String wrapEnc(String cipherTextBase64) {
        return ENC_PREFIX + cipherTextBase64 + ENC_SUFFIX;
    }

    public static String encrypt(String plaintext) throws Exception {
        SecretKey key = loadKey();
        byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
        byte[] cipherBytes = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        byte[] combined = new byte[iv.length + cipherBytes.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(cipherBytes, 0, combined, iv.length, cipherBytes.length);

        return Base64.getEncoder().encodeToString(combined);
    }

    public static String decrypt(String base64IvAndCipherText) throws Exception {
        SecretKey key = loadKey();
        byte[] combined = Base64.getDecoder().decode(base64IvAndCipherText);

        byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
        byte[] cipherBytes = new byte[combined.length - GCM_IV_LENGTH_BYTES];
        System.arraycopy(combined, 0, iv, 0, iv.length);
        System.arraycopy(combined, iv.length, cipherBytes, 0, cipherBytes.length);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
        byte[] plainBytes = cipher.doFinal(cipherBytes);

        return new String(plainBytes, StandardCharsets.UTF_8);
    }

    /**
     * 최초 설정 시 1회만 사용하는 마스터 키 생성.
     * 생성된 값을 collect 서버의 COLLECT_AUTH_SECRET_KEY 환경변수로 등록한다.
     */
    public static String generateKey() throws Exception {
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256);
        SecretKey key = keyGen.generateKey();
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    private static SecretKey loadKey() {
        String base64Key = System.getenv(ENV_KEY_NAME);
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(ENV_KEY_NAME + " 환경변수가 설정되어 있지 않다");
        }
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        return new SecretKeySpec(keyBytes, "AES");
    }
}
