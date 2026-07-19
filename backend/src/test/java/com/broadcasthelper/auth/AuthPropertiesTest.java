package com.broadcasthelper.auth;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic checks for the two-tier email allowlist: normalization, the
 * viewer/admin union for sign-in, and the admin check itself.
 */
class AuthPropertiesTest {

    @Test
    void normalizesTrimAndCaseAndDropsBlanks() {
        AuthProperties props = new AuthProperties(true,
                Arrays.asList("  Viewer@Example.COM ", "", "  ", null),
                Arrays.asList(" ADMIN@example.com "));
        assertTrue(props.allows("viewer@example.com"));
        assertTrue(props.allows("  VIEWER@example.com  "));
        assertTrue(props.isAdmin("admin@EXAMPLE.com"));
        assertFalse(props.allows(""));
        assertFalse(props.allows(null));
        assertFalse(props.isAdmin(null));
    }

    @Test
    void adminEmailsAreImplicitlyAllowedToSignIn() {
        AuthProperties props = new AuthProperties(true,
                List.of("viewer@example.com"), List.of("admin@example.com"));
        assertTrue(props.allows("admin@example.com"));
        assertTrue(props.allows("viewer@example.com"));
        assertFalse(props.allows("stranger@example.com"));
    }

    @Test
    void viewersAreNotAdmins() {
        AuthProperties props = new AuthProperties(true,
                List.of("viewer@example.com"), List.of("admin@example.com"));
        assertTrue(props.isAdmin("admin@example.com"));
        assertFalse(props.isAdmin("viewer@example.com"));
        assertFalse(props.isAdmin("stranger@example.com"));
    }

    @Test
    void nullListsAdmitNobody() {
        AuthProperties props = new AuthProperties(true, null, null);
        assertFalse(props.allows("anyone@example.com"));
        assertFalse(props.isAdmin("anyone@example.com"));
    }
}
