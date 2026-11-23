package io.github.mesmerprism.queststatusaudioexample;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RQG1 message authentication adapted from Rusty Quest's
 * ExperimentSessionGatewayProtocol at c3d2df89b644189efcf08279783e01d97a83f819.
 * One live controller in one headset session. All calls are main-owner confined.
 */
final class CompanionRequestAuth {
    private static final int MAX_NONCES = 4096;

    private final String controller;
    private final String key;
    private final String device;
    private final String epoch;
    private final Set<String> nonces = new HashSet<>();

    CompanionRequestAuth(String controller, String key, String device, String epoch) {
        this.controller = controller;
        this.key = key;
        this.device = device;
        this.epoch = epoch;
    }

    void accept(String nonce, String action, String payload, String proof)
            throws GeneralSecurityException {
        if (!isHex(nonce, 32) || !isHex(proof, 64)
                || !("status".equals(action) || "command".equals(action) || "receipt".equals(action))) {
            throw new SecurityException("Invalid request");
        }
        String expected = sign("RQG1|" + controller + "|" + epoch + "|" + nonce + "|" + action
                + "|" + payload);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                proof.getBytes(StandardCharsets.US_ASCII))) {
            throw new SecurityException("Invalid request proof");
        }
        if (nonces.size() >= MAX_NONCES || !nonces.add(nonce)) {
            throw new SecurityException("Request replay or capacity exhausted");
        }
    }

    String signReply(String nonce, String action, String payload) throws GeneralSecurityException {
        return sign("RQG1-REPLY|" + controller + "|" + device + "|" + epoch + "|" + nonce + "|"
                + action + "|" + payload);
    }

    private String sign(String message) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return hex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    }

    static boolean isHex(String value, int length) {
        return value != null && value.length() == length && value.matches("[0-9a-f]+");
    }

    static String hex(byte[] value) {
        StringBuilder text = new StringBuilder(value.length * 2);
        for (byte octet : value) {
            text.append(Character.forDigit((octet & 255) >>> 4, 16));
            text.append(Character.forDigit(octet & 15, 16));
        }
        return text.toString();
    }
}
