package com.broadcasthelper.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
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
 * Admin view + revoke over active sessions, behind Manage → Sessions. Covered by
 * the {@code /api/users/**} admin-only rule in {@link SecurityConfig}. Revoking a
 * session forces that person to sign in again; it does not remove their access
 * (that's removing them from the roster — {@link AppUserController}). Sessions
 * are addressed by primary_id, never the cookie's session_id; see
 * {@link UserSessions}.
 */
@RestController
@RequestMapping("/api/users/sessions")
public class UserSessionController {

    private final UserSessions sessions;

    public UserSessionController(UserSessions sessions) {
        this.sessions = sessions;
    }

    @GetMapping
    public List<UserSessions.UserSession> list(HttpServletRequest request) {
        HttpSession current = request.getSession(false);
        return sessions.list(current != null ? current.getId() : null);
    }

    @DeleteMapping("/{primaryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable String primaryId) {
        if (!sessions.revoke(primaryId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such session");
        }
    }

    /** Sign a user out everywhere. Zero matches is fine — no 404. */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeAll(@RequestParam String email) {
        sessions.revokeAllForEmail(email);
    }
}
