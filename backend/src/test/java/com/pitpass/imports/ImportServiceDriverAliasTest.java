package com.pitpass.imports;

import com.pitpass.drivers.DriverAdminController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A spelling retired by a driver merge must not mint the duplicate again on re-import. */
@SpringBootTest
@Transactional
class ImportServiceDriverAliasTest {

    @Autowired JdbcClient db;
    @Autowired ImportService imports;
    @Autowired DriverAdminController drivers;

    @Test
    void aMergedSpellingResolvesToTheSurvivorInBothLookups() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long target = imports.findOrCreateDriver("Jaden", "Munoz " + suffix, null, null);
        long source = imports.findOrCreateDriver("Jaden", "Munoz2 " + suffix, null, null);
        drivers.merge(target, new DriverAdminController.MergeRequest(source));
        int before = db.sql("SELECT count(*) FROM driver").query(Integer.class).single();

        assertEquals(target, imports.findOrCreateDriver("Jaden", "Munoz2 " + suffix, "US", "Miami"));
        // The entry-list path matches whole names, case and spacing ignored.
        assertEquals(target, imports.findOrCreateDriverByFullName("jaden  munoz2 " + suffix, null));
        assertEquals(before, db.sql("SELECT count(*) FROM driver").query(Integer.class).single());
        // What the source brought along is kept, as it would be for a name match.
        assertEquals("Miami", db.sql("SELECT hometown FROM driver WHERE id = :id")
                .param("id", target).query(String.class).single());
    }

    @Test
    void anExactNameStillBeatsAnAlias() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long keeper = imports.findOrCreateDriver("Alex", "Spetz " + suffix, null, null);
        long other = imports.findOrCreateDriver("Other", "Driver " + suffix, null, null);
        db.sql("INSERT INTO driver_alias (driver_id, alias) VALUES (:id, :alias)")
                .param("id", other).param("alias", "Alex Spetz " + suffix).update();

        assertEquals(keeper, imports.findOrCreateDriver("Alex", "Spetz " + suffix, null, null));
    }
}
