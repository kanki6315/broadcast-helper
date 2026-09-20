package com.pitpass.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.pitpass.auth.Principals;
import com.pitpass.live.LiveTimingService.LiveStatus;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The switch for the live timing connection, thrown from the app. Any member
 * may see the status; connecting and disconnecting are POSTs and so
 * admin-only by SecurityConfig's default. The raw feed tree is admin-only
 * too, by an explicit rule there.
 *
 * The Al Kamel host and credentials are deliberately not settable here: they
 * are ALKAMELV2_* env vars, and never travel through the app.
 */
@RestController
@RequestMapping("/api/live")
public class LiveTimingController {

    public record ConnectRequest(Long eventId) {
    }

    private final LiveTimingService service;
    private final LiveTimingStore store;
    private final LiveClassificationService classification;

    public LiveTimingController(LiveTimingService service, LiveTimingStore store,
                                LiveClassificationService classification) {
        this.service = service;
        this.store = store;
        this.classification = classification;
    }

    @GetMapping("/status")
    public LiveStatus status() {
        return service.status();
    }

    /**
     * The running order per class, matched to the bound event's entries — what
     * the championship calculators score. Members may read it: it is derived,
     * and it is what the feature exists to show them. Built for polling; the
     * body only changes when the order does, so the API's ETag answers 304.
     */
    @GetMapping("/classification")
    public LiveClassificationService.Response classification() {
        return classification.current();
    }

    /**
     * One class championship's rows against the running order: each row's live
     * scoring position by the championship's kind (teams, drivers,
     * manufacturers), and its imported qualifying position for the weekend.
     */
    @GetMapping("/championships/{id}")
    public LiveClassificationService.ChampionshipResponse championship(@PathVariable long id) {
        return classification.championship(id);
    }

    /** Asks for the connection and binds it to the event it will be scored against. */
    @PostMapping("/connect")
    public LiveStatus connect(@RequestBody ConnectRequest request, Authentication authentication) {
        if (!service.configured()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Live timing is not configured on this server (ALKAMELV2_HOST is not set)");
        }
        if (request == null || request.eventId() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Choose the event to score against");
        }
        if (store.eventName(request.eventId()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such event");
        }
        service.request(true, request.eventId(), who(authentication));
        return service.status();
    }

    @PostMapping("/disconnect")
    public LiveStatus disconnect(Authentication authentication) {
        service.request(false, null, who(authentication));
        return service.status();
    }

    /** The merged feed at a dotted path ("timing.session.info"); blank = everything held. Admin-only. */
    @GetMapping("/state")
    public JsonNode state(@RequestParam(defaultValue = "") String path) {
        JsonNode node = service.state(path);
        if (node == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Nothing at " + path);
        }
        return node;
    }

    private static String who(Authentication authentication) {
        String email = Principals.emailOf(authentication);
        return email == null ? "local" : email;
    }
}
