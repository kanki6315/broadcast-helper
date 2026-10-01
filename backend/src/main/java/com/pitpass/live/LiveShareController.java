package com.pitpass.live;

import com.pitpass.auth.Principals;
import com.pitpass.auth.ShareTokens;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Manage → Live timing's share links, one per person: the working ones, a new
 * one named for whoever will hold it, or revoking one (the rest keep working).
 * Admin-only, reads included, by an explicit rule in SecurityConfig. A secret
 * leaves the server only in POST's answer.
 */
@RestController
@RequestMapping("/api/live/share")
public class LiveShareController {

    static final int MAX_LABEL = 80;

    public record ShareState(List<ShareTokens.Link> links) {
    }

    public record NewLink(String label) {
    }

    private final ShareTokens tokens;

    public LiveShareController(ShareTokens tokens) {
        this.tokens = tokens;
    }

    @GetMapping
    public ShareState state() {
        return new ShareState(tokens.current());
    }

    /** Issues a new link for one person. The token is in this answer and nowhere else. */
    @PostMapping
    public ShareTokens.Issued issue(@RequestBody NewLink body, Authentication authentication) {
        String label = body == null || body.label() == null ? "" : body.label().strip();
        if (label.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Name who the link is for");
        }
        if (label.length() > MAX_LABEL) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Keep the name to " + MAX_LABEL + " characters");
        }
        return tokens.issue(label, Principals.emailOf(authentication));
    }

    @DeleteMapping("/{id}")
    public void revoke(@PathVariable long id) {
        if (!tokens.revoke(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No working link " + id);
        }
    }
}
