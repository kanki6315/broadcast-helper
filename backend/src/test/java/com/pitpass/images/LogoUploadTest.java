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
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static com.pitpass.images.LogoAssets.Kind.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@Transactional
class LogoUploadTest {
    @Autowired JdbcClient db;
    @Autowired LogoUploadController uploads;
    @Autowired LogoAssets assets;
    @Autowired PublicStorageMigrationController migration;
    @Autowired ManufacturerLogoController manufacturers;
    @Autowired SeriesLogoController seriesLogos;
    @Autowired com.pitpass.series.SeriesController seriesController;
    @Autowired com.pitpass.sheets.SheetController sheets;
    @MockitoBean PublicImageStorage storage;
    long series, event;
    String name;
    final byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8);

    @BeforeEach void fixture() {
        when(storage.enabled()).thenReturn(true);
        when(storage.uploadUrl(anyString(), anyString(), anyLong())).thenAnswer(i -> "https://upload.example/" + i.getArgument(0));
        when(storage.publicUrl(anyString())).thenAnswer(i -> URI.create("https://images.example/" + i.getArgument(0)));
        name = "maker-" + UUID.randomUUID();
        series = db.sql("INSERT INTO series(name) VALUES (:name) RETURNING id").param("name", name).query(Long.class).single();
        long season = db.sql("INSERT INTO season(series_id, year) VALUES (:id, 2026) RETURNING id").param("id", series).query(Long.class).single();
        event = db.sql("INSERT INTO event(season_id, name) VALUES (:id, 'Logos') RETURNING id").param("id", season).query(Long.class).single();
        db.sql("INSERT INTO entry(event_id, car_number, class_name, team_name, manufacturer) VALUES (:id, '23', 'GTP', 'Test', :name)")
                .param("id", event).param("name", name).update();
    }
    LogoUploadController.Plan plan(LogoAssets.Kind kind, String target) {
        return uploads.prepare(new LogoUploadController.Prepare(kind, target, "image/svg+xml", svg.length, null));
    }
    void legacyManufacturer() {
        db.sql("INSERT INTO manufacturer_logo(name, display_name, content_type, data, invert_on_dark) VALUES (:name, 'Display', 'image/svg+xml', :data, true)")
                .param("name", name).param("data", svg).update();
    }

    @Test void globalMigrationInventoryIncludesOnlyLegacyAssetsAndReportsDisabledStorage() {
        legacyManufacturer();
        long image = db.sql("INSERT INTO car_image(season_id,car_number,content_type,data) SELECT season_id,'99','image/png',:data FROM event WHERE id = :event RETURNING id")
                .param("data", new byte[]{1}).param("event", event).query(Long.class).single();
        long doc = db.sql("INSERT INTO event_document(event_id,kind,content_type,data) VALUES (:event,'STORYLINES','application/pdf',:data) RETURNING id")
                .param("event", event).param("data", new byte[]{1}).query(Long.class).single();
        var inventory = migration.inventory();
        assertTrue(inventory.enabled());
        assertTrue(inventory.photos().stream().anyMatch(p -> p.id() == image && p.originalUrl().startsWith("/api/car-images/")));
        assertTrue(inventory.documents().stream().anyMatch(d -> d.id() == doc));
        assertTrue(inventory.logos().stream().anyMatch(l -> l.asset().target().equals(name)));
        uploads.complete(plan(MANUFACTURER, name).id());
        assertFalse(migration.inventory().logos().stream().anyMatch(l -> l.asset().target().equals(name)));
        when(storage.enabled()).thenReturn(false);
        assertFalse(migration.inventory().enabled());
        assertTrue(migration.inventory().photos().isEmpty());
    }

    @Test void driverPhotoPublishesWithoutDatabaseBytesAndMigratesSafely() {
        long driver = db.sql("INSERT INTO driver(first_name,surname) VALUES ('Test','Driver') RETURNING id").query(Long.class).single();
        String target = Long.toString(driver);
        db.sql("INSERT INTO driver_photo(driver_id,content_type,data) VALUES (:id,'image/webp',:data)")
                .param("id", driver).param("data", new byte[]{1,2,3}).update();
        var source = assets.find(DRIVER, target, false).orElseThrow();
        var migration = uploads.prepare(new LogoUploadController.Prepare(DRIVER, target, "image/webp", 3, source.uploadedAt()));
        var moved = uploads.complete(migration.id());
        assertTrue(moved.logoUrl().startsWith("https://images.example/driver-photos/"));
        assertArrayEquals(new byte[]{1,2,3}, db.sql("SELECT data FROM driver_photo WHERE driver_id = :id").param("id", driver).query(byte[].class).single());
        var replacement = uploads.prepare(new LogoUploadController.Prepare(DRIVER, target, "image/webp", 5, null));
        var saved = uploads.complete(replacement.id());
        assertNotEquals(moved.logoUrl(), saved.logoUrl());
        assertTrue(db.sql("SELECT data IS NULL FROM driver_photo WHERE driver_id = :id").param("id", driver).query(Boolean.class).single());
        assertNull(assets.data(DRIVER, target, true).getBody());
        assertEquals(saved.logoUrl(), assets.data(DRIVER, target, true).getHeaders().getLocation().toString());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, assertThrows(ResponseStatusException.class,
                () -> uploads.prepare(new LogoUploadController.Prepare(DRIVER, target, "image/svg+xml", 3, null))).getStatusCode());
    }

    @Test void manufacturerUploadPreservesInversionAndUsesPublicUrlsOnSheetsAndManagement() {
        legacyManufacturer();
        var plan = plan(MANUFACTURER, "  " + name.toUpperCase() + "  ");
        var logo = uploads.complete(plan.id());
        assertTrue(logo.logoUrl().startsWith("https://images.example/manufacturer-logos/"));
        var row = manufacturers.manufacturers().stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
        assertEquals(logo.logoUrl(), row.logoUrl());
        assertEquals(true, row.invertOnDark());
        var sheet = sheets.sheet(event).classes().getFirst().entries().getFirst();
        assertEquals(logo.logoUrl(), sheet.manufacturerLogoUrl());
        assertTrue(sheet.manufacturerLogoInvert());
        assertTrue(db.sql("SELECT data IS NULL FROM manufacturer_logo WHERE name = :name").param("name", name).query(Boolean.class).single());
        assertEquals("Display", db.sql("SELECT display_name FROM manufacturer_logo WHERE name = :name").param("name", name).query(String.class).single());
        var response = manufacturers.data(name, "1");
        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        assertNull(response.getBody());
        verify(storage).publish(LogoUploadController.staging(plan.id()), "manufacturer-logos/" + plan.id() + "/logo", "image/svg+xml", svg.length);
    }

    @Test void seriesUploadAndRetryKeepTheNewestLogo() {
        var first = plan(SERIES, String.valueOf(series));
        uploads.complete(first.id());
        var second = plan(SERIES, String.valueOf(series));
        var current = uploads.complete(second.id());
        clearInvocations(storage);
        assertEquals(current.logoUrl(), uploads.complete(first.id()).logoUrl());
        verify(storage, never()).publish(anyString(), anyString(), anyString(), anyLong());
        var response = seriesController.list().stream().filter(s -> s.id() == series).findFirst().orElseThrow();
        assertEquals(current.logoUrl(), response.logoUrl());
        assertTrue(response.publicLogoStorage());
        assertEquals(URI.create(current.logoUrl()), seriesLogos.data(series, "1").getHeaders().getLocation());
        seriesLogos.delete(series);
        assertEquals(HttpStatus.GONE, assertThrows(ResponseStatusException.class, () -> uploads.complete(first.id())).getStatusCode());
    }

    @Test void migrationsPreserveOriginalBytesAndRejectChangedLogos() {
        legacyManufacturer();
        var source = assets.find(MANUFACTURER, name, false).orElseThrow();
        assertArrayEquals(svg, manufacturers.data(name, null).getBody());
        var plan = uploads.prepare(new LogoUploadController.Prepare(MANUFACTURER, name, "image/svg+xml", svg.length, source.uploadedAt()));
        uploads.complete(plan.id());
        assertArrayEquals(svg, db.sql("SELECT data FROM manufacturer_logo WHERE name = :name").param("name", name).query(byte[].class).single());
        db.sql("UPDATE manufacturer_logo SET object_key = NULL WHERE name = :name").param("name", name).update();
        source = assets.find(MANUFACTURER, name, false).orElseThrow();
        var stale = uploads.prepare(new LogoUploadController.Prepare(MANUFACTURER, name, "image/svg+xml", svg.length, source.uploadedAt()));
        db.sql("UPDATE manufacturer_logo SET uploaded_at = uploaded_at + interval '1 second' WHERE name = :name").param("name", name).update();
        clearInvocations(storage);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class, () -> uploads.complete(stale.id())).getStatusCode());
        verify(storage, never()).publish(anyString(), anyString(), anyString(), anyLong());
    }

    @Test void failureAndExpiryLeaveTheOriginalUntouchedAndMultipartIsDisabled() {
        legacyManufacturer();
        var expired = plan(MANUFACTURER, name);
        db.sql("UPDATE logo_upload SET expires_at = now() - interval '1 minute' WHERE id = :id").param("id", expired.id()).update();
        assertEquals(HttpStatus.GONE, assertThrows(ResponseStatusException.class, () -> uploads.complete(expired.id())).getStatusCode());
        var failed = plan(MANUFACTURER, name);
        doThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY)).when(storage).publish(anyString(), anyString(), anyString(), anyLong());
        assertThrows(ResponseStatusException.class, () -> uploads.complete(failed.id()));
        assertFalse(assets.find(MANUFACTURER, name, false).orElseThrow().publicStorage());
        assertArrayEquals(svg, manufacturers.data(name, null).getBody());
        var file = new MockMultipartFile("file", "logo.svg", "image/svg+xml", svg);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class, () -> manufacturers.upload(name, file)).getStatusCode());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class, () -> seriesLogos.upload(series, file)).getStatusCode());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, assertThrows(ResponseStatusException.class,
                () -> uploads.prepare(new LogoUploadController.Prepare(MANUFACTURER, name, "text/html", 10, null))).getStatusCode());
    }
}
