package com.pitpass.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;

/**
 * The native app's sign-in endpoints (docs/IOS.md):
 * <ol>
 *   <li>{@code GET /api/auth/device/start?name=iPad} — opened in the system
 *       browser sheet. Flags the session as a device login and bounces into
 *       the normal Google flow. Public: nobody is signed in yet.</li>
 *   <li>Google → Spring → {@link DeviceLoginSuccessHandler} → redirect to
 *       {@code pitpass://auth?code=…}, which closes the sheet.</li>
 *   <li>{@code POST /api/auth/device/exchange} with the code — the app's own
 *       request, no cookie — returns the bearer token once. Public for the
 *       same reason; the code is the credential.</li>
 * </ol>
 * {@code DELETE /api/auth/device} is the app's sign-out: it revokes exactly the
 * token that authenticated the call (a member() carve-out in SecurityConfig).
 */
@RestController
@RequestMapping("/api/auth/device")
public class DeviceAuthController {

    static final String GOOGLE_LOGIN = "/oauth2/authorization/google";
    static final String DEFAULT_DEVICE_NAME = "iPad";
    private static final int MAX_NAME_LENGTH = 80;

    private final DeviceTokens tokens;
    private final AuthProperties auth;

    public DeviceAuthController(DeviceTokens tokens, AuthProperties auth) {
        this.tokens = tokens;
        this.auth = auth;
    }

    public record ExchangeRequest(String code) {
    }

    @GetMapping("/start")
    public ResponseEntity<Void> start(@RequestParam(required = false) String name,
                                      HttpServletRequest request) {
        if (!auth.enabled()) {
            // The app checks /api/me first and skips sign-in entirely on an
            // open server, so this is only ever hit by hand.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Sign-in is not enabled on this server");
        }
        request.getSession(true).setAttribute(DeviceLoginSuccessHandler.DEVICE_LOGIN_ATTR, deviceName(name));
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(GOOGLE_LOGIN)).build();
    }

    @PostMapping("/exchange")
    public DeviceTokens.Issued exchange(@RequestBody ExchangeRequest request) {
        DeviceTokens.Issued issued = tokens.exchange(request.code());
        if (issued == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Sign-in code is invalid or expired — start again from the app");
        }
        return issued;
    }

    /** Sign this device out. Only meaningful for a bearer caller. */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void signOut(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof DeviceUser device)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Not signed in with a device token");
        }
        tokens.revoke(device.tokenId());
    }

    static String deviceName(String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty()) {
            return DEFAULT_DEVICE_NAME;
        }
        return name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH) : name;
    }
}
