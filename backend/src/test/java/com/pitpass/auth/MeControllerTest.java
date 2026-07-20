package com.pitpass.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The /api/me contract: auth off reports admin (dev mode shows the full UI);
 * auth on reports the signed-in user's tier from the directory, and signed-out
 * means not admin. The directory is stubbed by subclass (house pattern).
 */
class MeControllerTest {

    private static MeController controller(boolean enabled) {
        UserDirectory stub = new UserDirectory(null) {
            @Override
            public boolean allows(String email) {
                return email != null;
            }

            @Override
            public boolean isAdmin(String email) {
                return "admin@example.com".equals(email);
            }
        };
        return new MeController(new AuthProperties(enabled), stub);
    }

    @Test
    void authDisabledReportsAdmin() {
        assertEquals(new MeController.Me(false, null, true), controller(false).me(null));
    }

    @Test
    void signedOutReportsNotAdmin() {
        assertEquals(new MeController.Me(true, null, false), controller(true).me(null));
    }
}
