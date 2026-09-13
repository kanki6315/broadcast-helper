package com.pitpass.images;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.mock.web.MockMultipartFile;
import java.net.URI;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@Transactional
class CarImageUploadTest {
    @Autowired JdbcClient db;
    @Autowired CarImageUploadController uploads;
    @Autowired CarImageController images;
    @Autowired com.pitpass.browse.BrowseController browse;
    @Autowired com.pitpass.sheets.SheetController sheets;
    @MockitoBean PublicImageStorage storage;
    long season;
    long event;
    long entry;

    @BeforeEach
    void fixture() {
        when(storage.enabled()).thenReturn(true);
        when(storage.uploadUrl(anyString(), anyString(), anyLong())).thenAnswer(i -> "https://upload.example/" + i.getArgument(0));
        when(storage.publicUrl(anyString())).thenAnswer(i -> URI.create("https://images.example/" + i.getArgument(0)));
        long series = db.sql("INSERT INTO series(name) VALUES (:name) RETURNING id").param("name", "Images " + UUID.randomUUID()).query(Long.class).single();
        season = db.sql("INSERT INTO season(series_id, year) VALUES (:id, 2026) RETURNING id").param("id", series).query(Long.class).single();
        event = db.sql("INSERT INTO event(season_id, name) VALUES (:id, 'Images') RETURNING id").param("id", season).query(Long.class).single();
        entry = db.sql("INSERT INTO entry(event_id, car_number, class_name, team_name) VALUES (:id, '023', 'GTP', 'Test team') RETURNING id").param("id", event).query(Long.class).single();
    }

    CarImageUploadController.UploadPlan plan() {
        return uploads.prepare(new CarImageUploadController.PrepareRequest(season, "023", "023.jpg", "image/jpeg", 100, 30, null, null));
    }

    @Test void completionStoresOnlyKeysAndEveryReadReturnsPublicUrls() {
        var plan = plan();
        assertTrue(plan.originalUrl().contains("staging/car-images/"));
        var completed = uploads.complete(plan.id());
        String sheetUrl = "https://images.example/car-images/" + plan.id() + "/sheet";
        var summary = images.list(season).images().getFirst();
        assertEquals(sheetUrl, summary.imageUrl());
        assertTrue(summary.publicStorage());
        assertTrue(db.sql("SELECT data IS NULL FROM car_image WHERE id = :id").param("id", completed.id()).query(Boolean.class).single());
        assertEquals(sheetUrl, browse.event(event).entries().getFirst().imageUrl());
        assertEquals(sheetUrl, sheets.sheet(event).classes().getFirst().entries().getFirst().imageUrl());
        var redirect = images.entryImage(entry, "sheet", "1");
        assertEquals(HttpStatus.FOUND, redirect.getStatusCode());
        assertNull(redirect.getBody());
        assertEquals(sheetUrl, redirect.getHeaders().getLocation().toString());
        verify(storage).publish("staging/car-images/" + plan.id() + "/original", "car-images/" + plan.id() + "/original", "image/jpeg", 100);
        verify(storage).publish("staging/car-images/" + plan.id() + "/sheet", "car-images/" + plan.id() + "/sheet", "image/webp", 30);
    }

    @Test void retryCannotReplaceANewerUploadAndFailuresLeavePreviousImageIntact() {
        var first = plan();
        long image = uploads.complete(first.id()).id();
        var second = plan();
        assertTrue(uploads.complete(second.id()).replaced());
        clearInvocations(storage);
        assertEquals(image, uploads.complete(first.id()).id());
        verify(storage, never()).publish(anyString(), anyString(), anyString(), anyLong());
        String current = images.list(season).images().getFirst().imageUrl();
        var failed = plan();
        doThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "storage failed"))
                .when(storage).publish(anyString(), anyString(), eq("image/webp"), anyLong());
        assertThrows(ResponseStatusException.class, () -> uploads.complete(failed.id()));
        assertEquals(current, images.list(season).images().getFirst().imageUrl());
    }

    @Test void expiryAndDeletedImagesCannotBeCompleted() {
        var expired = plan();
        db.sql("UPDATE car_image_upload SET expires_at = now() - interval '1 minute' WHERE id = :id").param("id", expired.id()).update();
        assertEquals(HttpStatus.GONE, assertThrows(ResponseStatusException.class, () -> uploads.complete(expired.id())).getStatusCode());
        verify(storage, never()).publish(anyString(), anyString(), anyString(), anyLong());
        var valid = plan();
        long id = uploads.complete(valid.id()).id();
        images.delete(id);
        assertEquals(HttpStatus.GONE, assertThrows(ResponseStatusException.class, () -> uploads.complete(valid.id())).getStatusCode());
    }

    @Test void matchingPreservesLeadingZerosAndLegacyReadsUseExistingThumbnails() {
        var matches = uploads.match(new CarImageUploadController.MatchRequest(season, List.of("2026_023.jpg", "23.jpg")));
        assertEquals("023", matches.getFirst().carNumber());
        assertNull(matches.get(1).carNumber());
        long id = db.sql("""
                INSERT INTO car_image(season_id, car_number, content_type, data)
                VALUES (:s, '023', 'image/jpeg', :bytes) RETURNING id
                """).param("s", season).param("bytes", new byte[] {1}).query(Long.class).single();
        db.sql("INSERT INTO car_image_variant(image_id, variant, content_type, data) VALUES (:id, 'sheet', 'image/webp', :bytes)")
                .param("id", id).param("bytes", new byte[] {2, 3}).update();
        assertArrayEquals(new byte[] {2, 3}, images.imageData(id, "sheet", null).getBody());
        assertFalse(images.list(season).images().getFirst().publicStorage());
        assertTrue(images.list(season).images().getFirst().imageUrl().startsWith("/api/car-images/"));
        verify(storage, never()).publicUrl(anyString());
    }

    @Test void migrationRetainsBytesAndRejectsConcurrentReplacement() {
        long id = db.sql("""
                INSERT INTO car_image(season_id, car_number, source_filename, content_type, data)
                VALUES (:s, '023', '023.jpg', 'image/jpeg', :bytes) RETURNING id
                """).param("s", season).param("bytes", new byte[] {1, 2, 3}).query(Long.class).single();
        var source = images.list(season).images().getFirst();
        var plan = uploads.prepare(new CarImageUploadController.PrepareRequest(season, "023", "023.jpg", "image/jpeg", 3, 30,
                id, source.uploadedAt()));
        uploads.complete(plan.id());
        assertArrayEquals(new byte[] {1, 2, 3}, db.sql("SELECT data FROM car_image WHERE id = :id").param("id", id).query(byte[].class).single());
        assertTrue(images.list(season).images().getFirst().publicStorage());

        // Revert the migration references in this rolled-back fixture, then race a replacement.
        db.sql("UPDATE car_image SET original_object_key = NULL, sheet_object_key = NULL WHERE id = :id").param("id", id).update();
        source = images.list(season).images().getFirst();
        var stale = uploads.prepare(new CarImageUploadController.PrepareRequest(season, "023", "023.jpg", "image/jpeg", 3, 30,
                id, source.uploadedAt()));
        db.sql("UPDATE car_image SET uploaded_at = uploaded_at + interval '1 second' WHERE id = :id").param("id", id).update();
        clearInvocations(storage);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class, () -> uploads.complete(stale.id())).getStatusCode());
        verify(storage, never()).publish(anyString(), anyString(), anyString(), anyLong());
    }

    @Test void activeStorageRefusesLegacyMultipartUploadsAndSvg() {
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> images.uploadOne(season, "023", new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[] {1}))).getStatusCode());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, assertThrows(ResponseStatusException.class,
                () -> uploads.prepare(new CarImageUploadController.PrepareRequest(season, "023", "a.svg", "image/svg+xml", 10, 10, null, null))).getStatusCode());
    }
}
