package com.jarurat.mailer.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

public final class ApiKeyHasher {

    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeyHasher() {}

    /** API keys are already high entropy, so a fast digest is the right tool here. */
    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 256 bits of entropy behind a recognisable prefix. */
    public static String generate() {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        return "jcf_live_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    public static String prefixOf(String key) {
        return key.length() <= 16 ? key : key.substring(0, 16);
    }
}
