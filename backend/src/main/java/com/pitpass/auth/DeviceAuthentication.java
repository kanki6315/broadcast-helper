package com.pitpass.auth;

import org.springframework.security.authentication.AbstractAuthenticationToken;

import java.util.List;

/**
 * An already-verified bearer-token authentication. Carries no authorities on
 * purpose — like the Google session, roles are decided per request by
 * {@link LiveAuthorization} from the roster, never stamped into the token.
 */
public class DeviceAuthentication extends AbstractAuthenticationToken {

    private final DeviceUser user;

    public DeviceAuthentication(DeviceUser user) {
        super(List.of());
        this.user = user;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public DeviceUser getPrincipal() {
        return user;
    }

    @Override
    public String getName() {
        return user.email();
    }
}
