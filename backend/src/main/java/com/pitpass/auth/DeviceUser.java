package com.pitpass.auth;

import java.security.Principal;

/**
 * The principal behind a native-app bearer token: the roster email the token
 * was minted for plus which {@code device_token} row it is, so the app can
 * revoke exactly its own token on sign-out. {@link #getName()} is the email,
 * matching the web principal's rewrap in {@code SecurityConfig.emailNamed}.
 */
public record DeviceUser(long tokenId, String email, String deviceName) implements Principal {

    @Override
    public String getName() {
        return email;
    }
}
