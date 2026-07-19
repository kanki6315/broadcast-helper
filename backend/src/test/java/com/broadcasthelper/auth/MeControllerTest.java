package com.broadcasthelper.auth;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The /api/me contract: auth off reports admin (dev mode shows the full UI);
 * auth on reports the signed-in user's tier, and signed-out means not admin.
 */
class MeControllerTest {

    private static MeController controller(boolean enabled) {
        return new MeController(new AuthProperties(enabled,
                List.of("viewer@example.com"), List.of("admin@example.com")));
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
