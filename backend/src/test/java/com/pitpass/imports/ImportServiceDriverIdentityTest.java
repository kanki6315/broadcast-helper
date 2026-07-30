package com.pitpass.imports;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@Transactional
class ImportServiceDriverIdentityTest {

    @Autowired JdbcClient db;
    @Autowired ImportService service;

    private String storedName(long id) {
        return db.sql("SELECT first_name || '|' || surname FROM driver WHERE id = :id")
                .param("id", id).query(String.class).single();
    }

    /** The bug behind the Road America 120 re-upload failure: a results file
     *  shouting "CHAD GILSINGER" used to mint a case-twin of the entry-list
     *  driver, and the next entry-list commit then matched both rows. */
    @Test
    void allCapsResultsSpellingReusesTheEntryListDriver() {
        String suffix = UUID.randomUUID().toString();
        long fromEntryList = service.findOrCreateDriverByFullName("Chad Gilsinger-" + suffix, "USA");
        long fromResults = service.findOrCreateDriver(
                "CHAD", "GILSINGER-" + suffix.toUpperCase(), null, "Marysville, OH");

        assertEquals(fromEntryList, fromResults);
        assertEquals("Chad|Gilsinger-" + suffix, storedName(fromEntryList));
        // The shouting source still contributed the fields it alone carries.
        assertEquals("Marysville, OH", db.sql("SELECT hometown FROM driver WHERE id = :id")
                .param("id", fromEntryList).query(String.class).single());
    }

    @Test
    void properlyCasedSourceRecasesAnAllCapsRow() {
        String suffix = UUID.randomUUID().toString();
        long shouted = service.findOrCreateDriver(
                "CHAD", "GILSINGER-" + suffix.toUpperCase(), "USA", null);
        long recased = service.findOrCreateDriver("Chad", "Gilsinger-" + suffix, null, null);

        assertEquals(shouted, recased);
        assertEquals("Chad|Gilsinger-" + suffix, storedName(shouted));
    }

    @Test
    void entryListFullNameRecasesAnAllCapsRow() {
        String suffix = UUID.randomUUID().toString();
        long shouted = service.findOrCreateDriver(
                "CHAD", "GILSINGER-" + suffix.toUpperCase(), "USA", null);
        long fromEntryList = service.findOrCreateDriverByFullName("Chad Gilsinger-" + suffix, null);

        assertEquals(shouted, fromEntryList);
        assertEquals("Chad|Gilsinger-" + suffix, storedName(shouted));
    }

    /** A results-supplied split is the identity key; the entry list's naive
     *  first-space split must resolve to it, never rewrite it. */
    @Test
    void fullNameLookupKeepsTheSourceSuppliedSplit() {
        String suffix = UUID.randomUUID().toString();
        long fromResults = service.findOrCreateDriver("Juan Pablo", "Montoya-" + suffix, null, null);
        long fromEntryList = service.findOrCreateDriverByFullName("Juan Pablo Montoya-" + suffix, null);

        assertEquals(fromResults, fromEntryList);
        assertEquals("Juan Pablo|Montoya-" + suffix, storedName(fromResults));
    }
}
