package com.pitpass.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LiveWeatherTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String s) throws Exception {
        return mapper.readTree(s);
    }

    @Test
    void nothingSentIsNoWeather() throws Exception {
        assertNull(LiveWeather.now(null, null));
        assertNull(LiveWeather.now(json("{}"), json("{}")));
    }

    @Test
    void aSpecReadingIsTakenAsSent() throws Exception {
        var r = LiveWeather.now(json("""
                {"ambientTemperature":24.3,"ambientTemperatureF":75.7,"dayTime":1790000000000,"humidity":61.0,
                 "pressure":1012.4,"pressureInHg":29.9,"trackTemperature":38.25,"trackTemperatureF":100.85,
                 "windDirection":225,"windSpeed":14.0,"windSpeedMi":8.7}
                """), null);
        assertEquals(new LiveWeather.Reading(1790000000000L, 24.3, 75.7, 38.3, 100.9, 61.0, 1012.4, 29.9,
                225, 14.0, 8.7), r);
    }

    /** Protocol 1.0.33 predates the spec's US units: they are converted, and strings read as numbers. */
    @Test
    void missingUnitsAreConvertedFromTheOthers() throws Exception {
        var metric = LiveWeather.reading(json("""
                {"ambientTemperature":"20","trackTemperature":30,"pressure":1013.25,"windSpeed":16.09344,
                 "windDirection":-90,"humidity":null}
                """), 5L);
        assertEquals(new LiveWeather.Reading(5L, 20.0, 68.0, 30.0, 86.0, null, 1013.3, 29.92, 270, 16.1, 10.0), metric);

        var us = LiveWeather.reading(json("""
                {"ambientTemperatureF":50,"trackTemperatureF":95,"pressureInHg":30,"windSpeedMi":10}
                """), null);
        assertEquals(10.0, us.airC());
        assertEquals(35.0, us.trackC());
        assertEquals(1015.9, us.pressureMbar());
        assertEquals(16.1, us.windKmh());
    }

    @Test
    void theSessionIsOldestFirstAndTheTowerFallsBackToItsNewest() throws Exception {
        JsonNode session = json("""
                {"1790000120000":{"trackTemperature":40},"1790000000000":{"trackTemperature":38},
                 "1790000060000":{"dayTime":1790000060000,"trackTemperature":39},
                 "junk":"x","1790000180000":{"comment":"no reading"}}
                """);
        assertEquals(List.of(38.0, 39.0, 40.0),
                LiveWeather.session(session).stream().map(LiveWeather.Reading::trackC).toList());
        assertEquals(1790000120000L, LiveWeather.now(null, session).dayTimeMs());
        assertEquals(20.0, LiveWeather.now(json("{\"ambientTemperature\":20}"), session).airC(),
                "the latest reading wins over the session's");
    }
}
