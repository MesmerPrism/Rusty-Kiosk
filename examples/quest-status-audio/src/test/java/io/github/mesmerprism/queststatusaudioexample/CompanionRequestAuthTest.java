package io.github.mesmerprism.queststatusaudioexample;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class CompanionRequestAuthTest {
    private static final String CONTROLLER = "1".repeat(32);
    private static final String DEVICE = "2".repeat(32);
    private static final String NONCE = "3".repeat(32);
    private static final String KEY = "a".repeat(64);
    private static final String REQUEST_MAC =
            "ede41389ef1f4de160b4f5b9ce29d2daa42bfce8b547a86e03204375646e2a5c";
    private static final String REPLY_MAC =
            "fe55883820f69c39af96e4aa0959a149bffce930a31f8e911e7cc87443fa459b";

    @Test public void matchesGatewayVectorsAndRejectsReplay() throws Exception {
        CompanionRequestAuth auth = new CompanionRequestAuth(CONTROLLER, KEY, DEVICE, "534");
        auth.accept(NONCE, "status", "{}", REQUEST_MAC);
        assertEquals(REPLY_MAC, auth.signReply(NONCE, "status", "{}"));
        assertThrows(SecurityException.class, () -> auth.accept(NONCE, "status", "{}", REQUEST_MAC));
    }

    @Test public void authenticationFailureDoesNotConsumeNonce() throws Exception {
        CompanionRequestAuth auth = new CompanionRequestAuth(CONTROLLER, KEY, DEVICE, "534");
        assertThrows(SecurityException.class, () -> auth.accept(NONCE, "command", "{}", REQUEST_MAC));
        auth.accept(NONCE, "status", "{}", REQUEST_MAC);
    }
}
