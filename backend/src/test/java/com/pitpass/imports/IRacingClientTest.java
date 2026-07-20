package com.pitpass.imports;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The masking algorithm is the one part of the iRacing auth handshake that can
 * be tested without credentials, and the one part that fails silently: a wrong
 * mask looks exactly like a wrong password, and repeated failures lock the
 * client out. The expected digests are computed independently of the
 * implementation (base64 of sha256 over secret + lowercased identifier).
 */
class IRacingClientTest {

    @Test
    void masksASecretWithItsLowercasedIdentifier() {
        // echo -n "secretuser@example.com" | openssl dgst -sha256 -binary | base64
        assertEquals("ihBzlO6bnu0lfgFIo+8L4Sq3a+vl4rzfyPtyHKb2TO8=",
                IRacingClient.mask("secret", "user@example.com"));
    }

    @Test
    void normalizesTheIdentifierButNotTheSecret() {
        String canonical = IRacingClient.mask("secret", "user@example.com");
        // iRacing lowercases and trims the identifier, so these must agree...
        assertEquals(canonical, IRacingClient.mask("secret", "  USER@Example.COM  "));
        // ...while the secret itself is taken verbatim.
        assertNotEquals(canonical, IRacingClient.mask("SECRET", "user@example.com"));
    }

    @Test
    void saltsSoTheSameSecretDiffersPerIdentifier() {
        assertNotEquals(IRacingClient.mask("secret", "a@example.com"),
                IRacingClient.mask("secret", "b@example.com"));
    }

    @Test
    void isNotConfiguredWithoutEveryCredential() {
        assertFalse(client("", "", "", "").isConfigured());
        assertFalse(client("id", "secret", "user", "").isConfigured());
        assertFalse(client("id", "secret", "", "pass").isConfigured());
        assertTrue(client("id", "secret", "user", "pass").isConfigured());
    }

    private IRacingClient client(String id, String secret, String user, String pass) {
        return new IRacingClient(id, secret, user, pass, "https://example.invalid/token",
                "https://example.invalid/data");
    }
}
