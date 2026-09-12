package com.pitpass.auth;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Where a Google login lands. Browser logins go back to the SPA root as
 * before. A login the native app started (its start endpoint flagged the
 * session, see {@link DeviceAuthController}) instead mints a one-time code and
 * redirects to the app's custom scheme; the system browser sheet closes on
 * that URL and the app exchanges the code for a bearer token. The session is
 * invalidated on the way out: the app never uses it, and leaving a signed-in
 * session in the sign-in sheet's cookie jar would only clutter Manage →
 * Sessions.
 */
public class DeviceLoginSuccessHandler implements AuthenticationSuccessHandler {

    /** Session attribute holding the device name while the Google round-trip runs. */
    static final String DEVICE_LOGIN_ATTR = "pitPass.deviceLogin";
    /** Must match the URL scheme the iOS app registers (Info.plist) and the
     *  callback scheme it hands ASWebAuthenticationSession. */
    static final String APP_CALLBACK = "pitpass://auth";

    private final DeviceTokens tokens;
    private final SimpleUrlAuthenticationSuccessHandler web;

    public DeviceLoginSuccessHandler(DeviceTokens tokens) {
        this.tokens = tokens;
        // Equivalent of the previous defaultSuccessUrl("/", true).
        this.web = new SimpleUrlAuthenticationSuccessHandler("/");
        this.web.setAlwaysUseDefaultTargetUrl(true);
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication)
            throws IOException, ServletException {
        HttpSession session = request.getSession(false);
        Object deviceName = session != null ? session.getAttribute(DEVICE_LOGIN_ATTR) : null;
        if (deviceName == null) {
            web.onAuthenticationSuccess(request, response, authentication);
            return;
        }
        String code = tokens.issueCode(Principals.emailOf(authentication), deviceName.toString());
        session.invalidate();
        response.sendRedirect(APP_CALLBACK + "?code=" + URLEncoder.encode(code, StandardCharsets.UTF_8));
    }
}
