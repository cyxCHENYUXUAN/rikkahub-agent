package com.rikkahub.features.orchestration.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Validates cryptographic signatures for incoming external webhooks (GitHub, Feishu, Slack, Generic).
 * Prevents webhook spoofing and replay attacks.
 */
@Component
public final class WebhookSignatureVerifier {
    private static final HexFormat HEX = HexFormat.of();
    private static final long MAX_SLACK_TIMESTAMP_DRIFT_SECONDS = 300; // 5 minutes
    private final Environment environment;

    public WebhookSignatureVerifier(Environment environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    /**
     * Verifies the authenticity of an incoming webhook.
     *
     * @param provider external provider identifier (github, feishu, slack, generic)
     * @param rawBody the exact raw payload bytes
     * @param headers map of case-insensitive request headers
     * @return true if signature is valid; false otherwise
     */
    public boolean verify(String provider, byte[] rawBody, Map<String, String> headers) {
        if (provider == null || rawBody == null) {
            return false;
        }
        String secret = resolveSecret(provider);
        if (secret == null || secret.isBlank()) {
            return false; // Fail-closed if no secret is configured
        }

        String lowerProvider = provider.toLowerCase(Locale.ROOT);
        return switch (lowerProvider) {
            case "github" -> verifyGitHub(rawBody, headers, secret);
            case "feishu", "lark" -> verifyFeishu(rawBody, headers, secret);
            case "slack" -> verifySlack(rawBody, headers, secret);
            case "generic" -> verifyGeneric(rawBody, headers, secret);
            default -> false;
        };
    }

    private boolean verifyGitHub(byte[] rawBody, Map<String, String> headers, String secret) {
        String signature = getHeader(headers, "x-hub-signature-256");
        if (signature == null || !signature.startsWith("sha256=")) {
            return false;
        }
        String expectedHex = signature.substring(7).trim();
        byte[] expected = parseHex(expectedHex);
        if (expected == null) {
            return false;
        }
        byte[] actual = hmacSha256(secret.getBytes(StandardCharsets.UTF_8), rawBody);
        return MessageDigest.isEqual(expected, actual);
    }

    private boolean verifyFeishu(byte[] rawBody, Map<String, String> headers, String secret) {
        String timestamp = getHeader(headers, "x-lark-request-timestamp");
        String nonce = getHeader(headers, "x-lark-request-nonce");
        String signature = getHeader(headers, "x-lark-signature");
        if (timestamp == null || nonce == null || signature == null) {
            return false;
        }
        String bodyStr = new String(rawBody, StandardCharsets.UTF_8);
        String toSign = timestamp + nonce + secret + bodyStr;
        byte[] digest = sha256(toSign.getBytes(StandardCharsets.UTF_8));
        byte[] expected = parseHex(signature.trim());
        if (expected == null) {
            return false;
        }
        return MessageDigest.isEqual(expected, digest);
    }

    private boolean verifySlack(byte[] rawBody, Map<String, String> headers, String secret) {
        String timestampStr = getHeader(headers, "x-slack-request-timestamp");
        String signature = getHeader(headers, "x-slack-signature");
        if (timestampStr == null || signature == null || !signature.startsWith("v0=")) {
            return false;
        }
        try {
            long timestamp = Long.parseLong(timestampStr);
            long now = Instant.now().getEpochSecond();
            if (Math.abs(now - timestamp) > MAX_SLACK_TIMESTAMP_DRIFT_SECONDS) {
                return false; // Prevent replay attacks
            }
        } catch (NumberFormatException e) {
            return false;
        }
        String bodyStr = new String(rawBody, StandardCharsets.UTF_8);
        String basestring = "v0:" + timestampStr + ":" + bodyStr;
        byte[] expected = parseHex(signature.substring(3).trim());
        if (expected == null) {
            return false;
        }
        byte[] actual = hmacSha256(secret.getBytes(StandardCharsets.UTF_8), basestring.getBytes(StandardCharsets.UTF_8));
        return MessageDigest.isEqual(expected, actual);
    }

    private boolean verifyGeneric(byte[] rawBody, Map<String, String> headers, String secret) {
        String signature = getHeader(headers, "x-signature-sha256");
        if (signature == null) {
            return false;
        }
        byte[] expected = parseHex(signature.trim());
        if (expected == null) {
            return false;
        }
        byte[] actual = hmacSha256(secret.getBytes(StandardCharsets.UTF_8), rawBody);
        return MessageDigest.isEqual(expected, actual);
    }

    public String resolveSecret(String provider) {
        String specificKey = "WEBHOOK_SECRET_" + provider.toUpperCase(Locale.ROOT);
        String secret = environment.getProperty(specificKey);
        if (secret != null && !secret.isBlank()) {
            return secret;
        }
        return environment.getProperty("WEBHOOK_SECRET");
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("hmac_computation_failed", e);
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("sha256_computation_failed", e);
        }
    }

    private static byte[] parseHex(String hex) {
        try {
            return HEX.parseHex(hex);
        } catch (Exception e) {
            return null;
        }
    }

    private static String getHeader(Map<String, String> headers, String key) {
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
