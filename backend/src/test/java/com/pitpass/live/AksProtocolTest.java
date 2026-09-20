package com.pitpass.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Frames and diffs, checked against the examples printed in the AKS V2 spec (1.0.36). */
class AksProtocolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode json(String text) throws Exception {
        return (ObjectNode) mapper.readTree(text);
    }

    @Test
    void onlyTheFirstThreeColonsAreStructure() {
        AksFrame frame = AksFrame.parse("JSON:3::{\"timing\":{\"session\":{\"info\":{\"name\":\"Race: Part 2\"}}}}");
        assertEquals("JSON", frame.command());
        assertEquals("3", frame.messageId());
        assertEquals("", frame.channel());
        assertEquals("{\"timing\":{\"session\":{\"info\":{\"name\":\"Race: Part 2\"}}}}",
                new String(frame.data(), StandardCharsets.UTF_8));
    }

    @Test
    void repliesEchoTheIdWithAPlus() {
        AksFrame join = AksFrame.parse("JOIN:23+:timing.session.info:");
        assertTrue(join.isReplyTo(23));
        assertFalse(join.isReplyTo(2));
        assertEquals("timing.session.info", join.channel());
        assertFalse(join.hasData());
        assertTrue(AksFrame.parse("ACK:7+::").isReplyTo(7));
    }

    @Test
    void theSpecsOwnSpacedExamplesParse() {
        AksFrame frame = AksFrame.parse("JSON: : : { \"timing\": null }");
        assertEquals("JSON", frame.command());
        assertEquals("", frame.messageId());
        assertEquals("{ \"timing\": null }", new String(frame.data(), StandardCharsets.UTF_8));
    }

    @Test
    void aLineThatIsNotAFrameIsRefused() {
        assertThrows(AksFrame.MalformedFrame.class, () -> AksFrame.parse("HTTP/1.1 400 Bad Request"));
    }

    @Test
    void clientCommandsEndInCrLf() {
        assertEquals("JOIN:4:timing.session.info:\r\n",
                new String(AksFrame.encode("JOIN", 4, "timing.session.info", ""), StandardCharsets.UTF_8));
        assertEquals("PING:5::\r\n", new String(AksFrame.encode("PING", 5, "", ""), StandardCharsets.UTF_8));
    }

    @Test
    void aDiffUpdatesOnlyWhatItNames() throws Exception {
        AksStateTree tree = new AksStateTree();
        tree.merge(json("""
                {"timing":{"session":{"entry":{
                  "5":{"number":"5","class":"LMP1","team":"A"},
                  "7":{"number":"7","class":"LMP1","team":"B"}}}}}
                """));
        // Spec §3: "Update participant 5 data (class = NewClass)"
        tree.merge(json("{\"timing\":{\"session\":{\"entry\":{\"5\":{\"class\":\"NewClass\"}}}}}"));

        assertEquals("NewClass", tree.copyOf("timing.session.entry.5").path("class").asText());
        assertEquals("A", tree.copyOf("timing.session.entry.5").path("team").asText());
        assertEquals("LMP1", tree.copyOf("timing.session.entry.7").path("class").asText());
    }

    @Test
    void nullDeletesAFieldOrAWholeChannel() throws Exception {
        AksStateTree tree = new AksStateTree();
        tree.merge(json("{\"timing\":{\"session\":{\"entry\":{\"5\":{\"class\":\"LMP1\",\"team\":\"A\"}}}}}"));

        // Spec §3: "Delete participant 5 class field (cleared)"
        tree.merge(json("{\"timing\":{\"session\":{\"entry\":{\"5\":{\"class\":null}}}}}"));
        assertFalse(tree.copyOf("timing.session.entry.5").has("class"));
        assertEquals("A", tree.copyOf("timing.session.entry.5").path("team").asText());

        // Spec §3: "Delete current session (new session loaded)"
        tree.merge(json("{\"timing\":null}"));
        assertNull(tree.copyOf("timing"));
    }

    @Test
    void aNullInsideABrandNewChannelIsNotStored() throws Exception {
        AksStateTree tree = new AksStateTree();
        tree.merge(json("{\"timing\":{\"session\":{\"status\":{\"currentFlag\":\"GREEN\",\"closeTime\":null}}}}"));
        assertFalse(tree.copyOf("timing.session.status").has("closeTime"));
    }

    @Test
    void copiesAreDetachedFromLaterMerges() throws Exception {
        AksStateTree tree = new AksStateTree();
        tree.merge(json("{\"timing\":{\"session\":{\"status\":{\"currentFlag\":\"GREEN\"}}}}"));
        var before = tree.copyOf("timing.session.status");
        tree.merge(json("{\"timing\":{\"session\":{\"status\":{\"currentFlag\":\"RED\"}}}}"));
        assertEquals("GREEN", before.path("currentFlag").asText());
        assertEquals("RED", tree.copyOf("timing.session.status").path("currentFlag").asText());
    }
}
