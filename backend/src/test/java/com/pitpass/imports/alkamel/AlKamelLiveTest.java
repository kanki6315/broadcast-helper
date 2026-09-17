package com.pitpass.imports.alkamel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One real request to the results site, run by hand only
 * ({@code ALKAMEL_LIVE=1 ./gradlew test --tests '*AlKamelLiveTest'}): proves the
 * recorded listings still match what the site serves — the index markup, the
 * year folders, the encoded hrefs. Skipped everywhere else so CI never touches
 * the site.
 */
@EnabledIfEnvironmentVariable(named = "ALKAMEL_LIVE", matches = "1")
class AlKamelLiveTest {

    @Test
    void rootListingStillParses() {
        AlKamelClient client = new AlKamelClient(
                "https://imsa.results.alkamelcloud.com/Results/", 350, 600, 1024 * 1024);
        AlKamelIndex index = new AlKamelIndex(client);
        List<AlKamelIndex.YearFolder> years = index.years();
        assertTrue(years.size() >= 11, "years: " + years);
        assertEquals(2016, years.get(0).year());
        assertEquals("16_2016/", years.get(0).path());
        assertTrue(years.stream().anyMatch(y -> y.year() == 2026));
    }
}
