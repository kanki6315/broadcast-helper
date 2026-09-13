package com.pitpass.series;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class SeriesControllerTest {

    @org.springframework.test.context.bean.override.mockito.MockitoBean com.pitpass.images.PublicImageStorage storage;
    @org.junit.jupiter.api.BeforeEach void publicStorage() {
        org.mockito.Mockito.when(storage.enabled()).thenReturn(true);
        org.mockito.Mockito.when(storage.publicUrl(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(i -> java.net.URI.create("https://images.example/" + i.getArgument(0)));
    }

    @Autowired SeriesController controller;
    @Autowired SeriesRepository repository;
    @Autowired JdbcClient db;

    @Test
    void createAndUpdatePreserveIdentityTimestampAndRelatedMetadata() {
        String name = "Series " + UUID.randomUUID();
        var created = controller.create(new SeriesController.CreateSeriesRequest("  " + name + "  ", null));
        assertNotNull(created.id());
        assertEquals(name, created.name());
        assertNull(created.abbreviation());
        Series stored = repository.findById(created.id()).orElseThrow();
        assertNotNull(stored.getCreatedAt());

        controller.setPrimaryKind(created.id(), new SeriesController.PrimaryKindRequest("DRIVERS"));
        String alias = "Alias " + UUID.randomUUID();
        controller.addAlias(created.id(), new SeriesController.AddAliasRequest("  " + alias + "  "));
        OffsetDateTime uploaded = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        db.sql("""
                INSERT INTO series_logo (series_id, content_type, object_key, uploaded_at)
                VALUES (:id, 'image/png', :data, :uploaded)
                """).param("id", created.id()).param("data", "series-logos/fixture/logo")
                .param("uploaded", uploaded).update();

        var updated = controller.update(created.id(),
                new SeriesController.UpdateSeriesRequest("  " + name + " renamed  ", "  NEW  "));
        assertEquals(created.id(), updated.id());
        assertEquals(name + " renamed", updated.name());
        assertEquals("NEW", updated.abbreviation());
        assertEquals("DRIVERS", updated.primaryKind());
        assertEquals(List.of(alias), updated.aliases());
        assertEquals(uploaded.toInstant().toEpochMilli(), updated.logoVersion());
        assertEquals(stored.getCreatedAt().toInstant(),
                repository.findById(created.id()).orElseThrow().getCreatedAt().toInstant());
        assertEquals(updated, controller.list().stream().filter(s -> s.id().equals(created.id())).findFirst().orElseThrow());

        var cleared = controller.update(created.id(),
                new SeriesController.UpdateSeriesRequest(updated.name(), "  "));
        assertNull(cleared.abbreviation());
    }

    @Test
    void listOrdersSeriesByName() {
        String suffix = UUID.randomUUID().toString();
        var z = controller.create(new SeriesController.CreateSeriesRequest("Z " + suffix, "Z"));
        var a = controller.create(new SeriesController.CreateSeriesRequest("A " + suffix, "A"));
        var ids = controller.list().stream().map(SeriesController.SeriesResponse::id)
                .filter(id -> id.equals(a.id()) || id.equals(z.id())).toList();
        assertEquals(List.of(a.id(), z.id()), ids);
    }

    @Test
    void duplicateNamesAreCaseInsensitiveAndMissingSeriesReturn404() {
        String name = "Series " + UUID.randomUUID();
        var first = controller.create(new SeriesController.CreateSeriesRequest(name, "ONE"));
        var second = controller.create(new SeriesController.CreateSeriesRequest(name + " second", "TWO"));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> controller.create(new SeriesController.CreateSeriesRequest(name.toUpperCase(), null))).getStatusCode());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> controller.update(second.id(), new SeriesController.UpdateSeriesRequest(name.toUpperCase(), null))).getStatusCode());
        assertEquals(name.toUpperCase(), controller.update(first.id(),
                new SeriesController.UpdateSeriesRequest(name.toUpperCase(), null)).name());
        assertTrue(repository.findById(-1L).isEmpty());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> controller.update(-1L, new SeriesController.UpdateSeriesRequest(name, null))).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> controller.addAlias(-1L, new SeriesController.AddAliasRequest("missing"))).getStatusCode());
    }
}
