package com.pitpass.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LiveRaceControlTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String s) throws Exception {
        return mapper.readTree(s);
    }

    @Test
    void nothingSentIsNoStrip() {
        assertNull(LiveRaceControl.now(null, null));
    }

    @Test
    void theLogIsNewestFirstByTimeShownThenKey() throws Exception {
        JsonNode messages = json("""
                {"100":{"dayTime":2000,"text":"B"},"300":{"dayTime":1000,"text":"A"},
                 "200":{"dayTime":2000,"text":"C"},"400":{"text":"no time"},
                 "500":{"dayTime":3000,"text":"","isNull":false},"600":{"dayTime":4000,"text":"gone","isNull":true}}
                """);
        assertEquals(List.of("C", "B", "A", "no time"),
                LiveRaceControl.log(messages).stream().map(LiveRaceControl.Message::text).toList());
    }

    @Test
    void theScreensLinesComeFromTheirKeysWhenTheyCarryNoLine() throws Exception {
        var now = LiveRaceControl.now(json("""
                {"3":{"text":"SAFETY CAR","backgroundColor":"#FFFF00","blink":true},"1":{"text":"GREEN"}}
                """), json("{}"));
        assertEquals(List.of(1, 3), now.lines().stream().map(LiveRaceControl.Message::line).toList());
        assertEquals("#ffff00", now.lines().get(1).background());
        assertEquals(true, now.lines().get(1).blink());
        assertNull(now.latest());
    }

    @Test
    void onlyHexColoursPass() {
        assertEquals("#00ff00", LiveRaceControl.color(" #00FF00 "));
        assertNull(LiveRaceControl.color("red"));
        assertNull(LiveRaceControl.color("#fff"));
        assertNull(LiveRaceControl.color("#ffffff;background:url(x)"));
    }
}
