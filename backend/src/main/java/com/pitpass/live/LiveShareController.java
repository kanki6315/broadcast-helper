package com.pitpass.live;

import com.pitpass.auth.Principals;
import com.pitpass.auth.ShareTokens;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Manage → Live timing's share link: whether one works, a new one (the old
 * stops working), or none. Admin-only, reads included, by an explicit rule in
 * SecurityConfig. The secret leaves the server only in POST's answer.
 */
@RestController
@RequestMapping("/api/live/share")
public class LiveShareController {

    /** link is null when no link works. */
    public record ShareState(ShareTokens.Link link) {
    }

    private final ShareTokens tokens;

    public LiveShareController(ShareTokens tokens) {
        this.tokens = tokens;
    }

    @GetMapping
    public ShareState state() {
        return new ShareState(tokens.current().orElse(null));
    }

    /** Issues a new link, revoking the working one. The token is in this answer and nowhere else. */
    @PostMapping
    public ShareTokens.Issued issue(Authentication authentication) {
        return tokens.issue(Principals.emailOf(authentication));
    }

    @DeleteMapping
    public void revoke() {
        if (!tokens.revoke()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No link to revoke");
        }
    }
}
