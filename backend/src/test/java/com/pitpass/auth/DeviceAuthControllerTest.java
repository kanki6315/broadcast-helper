package com.pitpass.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The app's sign-in endpoints called directly on the bean (house pattern).
 * The default test context runs with auth off, so the auth-on start path is
 * exercised on a hand-built controller.
 */
@SpringBootTest
@Transactional
class DeviceAuthControllerTest {

    @Autowired
    private DeviceAuthController controller;

    @Autowired
    private DeviceTokens tokens;

    @Test
    void startFlagsTheSessionAndBouncesIntoGoogle() {
        DeviceAuthController enabled = new DeviceAuthController(tokens, new AuthProperties(true));
        MockHttpServletRequest request = new MockHttpServletRequest();

        ResponseEntity<Void> r = enabled.start("  Booth iPad  ", request);

        assertEquals(HttpStatus.FOUND, r.getStatusCode());
        assertEquals(DeviceAuthController.GOOGLE_LOGIN, r.getHeaders().getLocation().toString());
        assertEquals("Booth iPad", request.getSession(false).getAttribute(DeviceLoginSuccessHandler.DEVICE_LOGIN_ATTR));
    }

    @Test
    void startDefaultsAndClampsTheDeviceName() {
        assertEquals("iPad", DeviceAuthController.deviceName(null));
        assertEquals("iPad", DeviceAuthController.deviceName("   "));
        assertEquals(80, DeviceAuthController.deviceName("x".repeat(500)).length());
    }

    @Test
    void startIsRefusedWhenAuthIsOff() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.start("iPad", new MockHttpServletRequest()));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
    }

    @Test
    void exchangeReturnsATokenOnceAndRejectsBadCodes() {
        String code = tokens.issueCode("app@example.test", "iPad");
        DeviceTokens.Issued issued = controller.exchange(new DeviceAuthController.ExchangeRequest(code));
        assertNotNull(issued.token());
        assertEquals("app@example.test", issued.email());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.exchange(new DeviceAuthController.ExchangeRequest(code)));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
    }

    @Test
    void signOutRevokesOnlyTheCallingToken() {
        DeviceTokens.Issued mine = tokens.exchange(tokens.issueCode("me@example.test", "iPad"));
        DeviceTokens.Issued other = tokens.exchange(tokens.issueCode("me@example.test", "Other iPad"));
        DeviceUser caller = tokens.resolve(mine.token());

        controller.signOut(new DeviceAuthentication(caller));

        assertNull(tokens.resolve(mine.token()));
        assertNotNull(tokens.resolve(other.token()));
    }

    @Test
    void signOutWithoutADeviceTokenIsABadRequest() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.signOut(null));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
    }
}
