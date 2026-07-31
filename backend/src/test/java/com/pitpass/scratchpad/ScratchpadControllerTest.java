package com.pitpass.scratchpad;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class ScratchpadControllerTest {

    @Autowired JdbcClient db;
    @Autowired ScratchpadController controller;
    @Autowired ObjectMapper json;

    private long eventId() {
        long seriesId = db.sql("INSERT INTO series (name) VALUES (:n) RETURNING id")
                .param("n", "Scratchpad series " + UUID.randomUUID()).query(Long.class).single();
        long seasonId = db.sql("INSERT INTO season (series_id, year) VALUES (:s, 2099) RETURNING id")
                .param("s", seriesId).query(Long.class).single();
        return db.sql("INSERT INTO event (season_id, name) VALUES (:s, 'Round') RETURNING id")
                .param("s", seasonId).query(Long.class).single();
    }

    private static OidcUser user(String email) {
        OidcIdToken token = new OidcIdToken("token", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("sub", "test-sub", "email", email));
        return new DefaultOidcUser(List.of(), token, "email");
    }

    private ArrayNode strokes(String jsonArray) throws Exception {
        return (ArrayNode) json.readTree(jsonArray);
    }

    @Test
    void getBeforeAnySaveSynthesizesAnEmptyPad() {
        long event = eventId();
        ScratchpadController.Pad pad = controller.get(event, user("a@example.test"));
        assertEquals(0, pad.revision());
        assertEquals(2000, pad.pageHeight());
        assertTrue(pad.strokes().isArray());
        assertEquals(0, pad.strokes().size());
    }

    @Test
    void firstSaveInsertsAndRoundtripsStrokesVerbatim() throws Exception {
        long event = eventId();
        OidcUser caller = user("a@example.test");
        ArrayNode drawn = strokes("""
                [{"tool":"pen","color":"#1d4ed8","size":4,"points":[412,118,415,121,419,127]}]
                """);
        ScratchpadController.SaveResponse saved = controller.save(event, caller,
                new ScratchpadController.SaveRequest(0, 3000, drawn));
        assertEquals(1, saved.revision());
        assertNotNull(saved.updatedAt());

        ScratchpadController.Pad pad = controller.get(event, caller);
        assertEquals(1, pad.revision());
        assertEquals(3000, pad.pageHeight());
        assertEquals(drawn, pad.strokes());
    }

    @Test
    void revisionAdvancesAndAStaleBaseIsConflict() throws Exception {
        long event = eventId();
        OidcUser caller = user("a@example.test");
        controller.save(event, caller, new ScratchpadController.SaveRequest(0, 2000, strokes("[]")));
        ScratchpadController.SaveResponse second = controller.save(event, caller,
                new ScratchpadController.SaveRequest(1, 2000, strokes("[]")));
        assertEquals(2, second.revision());

        // The stale tab still holds revision 1; its save must lose, not clobber.
        ResponseStatusException stale = assertThrows(ResponseStatusException.class,
                () -> controller.save(event, caller,
                        new ScratchpadController.SaveRequest(1, 2000, strokes("[]"))));
        assertEquals(HttpStatus.CONFLICT, stale.getStatusCode());

        // Same for a second "first save" racing an insert that already landed.
        ResponseStatusException raced = assertThrows(ResponseStatusException.class,
                () -> controller.save(event, caller,
                        new ScratchpadController.SaveRequest(0, 2000, strokes("[]"))));
        assertEquals(HttpStatus.CONFLICT, raced.getStatusCode());
    }

    @Test
    void padsAreIndependentPerUserAndEmailCaseInsensitive() throws Exception {
        long event = eventId();
        controller.save(event, user("a@example.test"),
                new ScratchpadController.SaveRequest(0, 2000,
                        strokes("[{\"tool\":\"pen\",\"color\":\"#111\",\"size\":2,\"points\":[1,1,2,2]}]")));

        // Another user sees an untouched pad on the same event.
        ScratchpadController.Pad other = controller.get(event, user("b@example.test"));
        assertEquals(0, other.revision());
        assertEquals(0, other.strokes().size());

        // The same person with different email casing sees the same pad.
        ScratchpadController.Pad recased = controller.get(event, user("A@Example.Test"));
        assertEquals(1, recased.revision());
        assertEquals(1, recased.strokes().size());
    }

    @Test
    void nullPrincipalMapsToTheDevSentinel() throws Exception {
        long event = eventId();
        controller.save(event, null, new ScratchpadController.SaveRequest(0, 2000,
                strokes("[{\"tool\":\"pen\",\"color\":\"#111\",\"size\":2,\"points\":[1,1,2,2]}]")));
        String owner = db.sql("SELECT owner_email FROM event_scratchpad WHERE event_id = :e")
                .param("e", event).query(String.class).single();
        assertEquals(ScratchpadController.DEV_OWNER, owner);
        assertEquals(1, controller.get(event, null).revision());
    }

    @Test
    void malformedSavesAreRejected() throws Exception {
        long event = eventId();
        OidcUser caller = user("a@example.test");

        ResponseStatusException notArray = assertThrows(ResponseStatusException.class,
                () -> controller.save(event, caller, new ScratchpadController.SaveRequest(0, 2000,
                        json.readTree("{\"tool\":\"pen\"}"))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, notArray.getStatusCode());

        ResponseStatusException tooShort = assertThrows(ResponseStatusException.class,
                () -> controller.save(event, caller,
                        new ScratchpadController.SaveRequest(0, 100, strokes("[]"))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, tooShort.getStatusCode());

        ArrayNode oversized = json.createArrayNode();
        oversized.add("x".repeat(2_000_001));
        ResponseStatusException tooBig = assertThrows(ResponseStatusException.class,
                () -> controller.save(event, caller,
                        new ScratchpadController.SaveRequest(0, 2000, oversized)));
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, tooBig.getStatusCode());
    }

    @Test
    void unknownEventIs404() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.get(-1, user("a@example.test")));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }
}
