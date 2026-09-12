package com.pitpass.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Admin view + revoke over linked devices (native-app bearer tokens), the
 * device counterpart of {@link UserSessionController} on Manage → Sessions.
 * Covered by the {@code /api/users/**} admin-only rule. Revoking signs that
 * device out; access itself is still the roster's call.
 */
@RestController
@RequestMapping("/api/users/devices")
public class UserDeviceController {

    private final DeviceTokens tokens;

    public UserDeviceController(DeviceTokens tokens) {
        this.tokens = tokens;
    }

    @GetMapping
    public List<DeviceTokens.Device> list() {
        return tokens.list();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable long id) {
        if (!tokens.revoke(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such device");
        }
    }

    /** Sign a user's devices out everywhere. Zero matches is fine — no 404. */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeAll(@RequestParam String email) {
        tokens.revokeAllForEmail(email);
    }
}
