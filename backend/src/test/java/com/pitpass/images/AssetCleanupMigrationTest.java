package com.pitpass.images;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import javax.sql.DataSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class AssetCleanupMigrationTest {
    @Autowired DataSource source;

    @Test void cleanupRejectsMissingReferencesThenDropsBytesAndPreservesMetadata() throws Exception {
        String schema = "cleanup_" + UUID.randomUUID().toString().replace("-", "");
        var flyway = Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).target("49").load();
        flyway.migrate();
        try (var connection = source.getConnection()) {
            connection.setSchema(schema);
            var db = JdbcClient.create(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));
            long series = db.sql("INSERT INTO series(name) VALUES ('Cleanup') RETURNING id").query(Long.class).single();
            long season = db.sql("INSERT INTO season(series_id,year) VALUES (:id,2026) RETURNING id").param("id", series).query(Long.class).single();
            long event = db.sql("INSERT INTO event(season_id,name) VALUES (:id,'Cleanup') RETURNING id").param("id", season).query(Long.class).single();
            long driver = db.sql("INSERT INTO driver(first_name,surname) VALUES ('Clean','Up') RETURNING id").query(Long.class).single();
            db.sql("INSERT INTO car_image(season_id,car_number,content_type,data) VALUES (:id,'23','image/jpeg',decode('01','hex'))").param("id", season).update();
            db.sql("INSERT INTO manufacturer_logo(name,display_name,content_type,data,invert_on_dark) VALUES ('test','Test','image/svg+xml',decode('02','hex'),true)").update();
            db.sql("INSERT INTO series_logo(series_id,content_type,data) VALUES (:id,'image/png',decode('03','hex'))").param("id", series).update();
            db.sql("INSERT INTO driver_photo(driver_id,content_type,data) VALUES (:id,'image/webp',decode('04','hex'))").param("id", driver).update();
            long document = db.sql("INSERT INTO event_document(event_id,content_type,data,page_count) VALUES (:id,'application/pdf',decode('05','hex'),8) RETURNING id").param("id", event).query(Long.class).single();
            db.sql("INSERT INTO event_document_page(document_id,car_number,page) VALUES (:id,'23',4)").param("id", document).update();
            var cleanup = Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).target("50").load();
            for (String table : new String[]{"car_image", "manufacturer_logo", "series_logo", "driver_photo", "event_document"}) {
                assertThrows(FlywayException.class, cleanup::migrate);
                assertTrue(db.sql("SELECT data IS NOT NULL FROM " + table).query(Boolean.class).single());
                db.sql(table.equals("car_image")
                        ? "UPDATE car_image SET original_object_key = 'car-images/test/original', sheet_object_key = 'car-images/test/sheet'"
                        : "UPDATE " + table + " SET object_key = 'assets/test/file'").update();
            }
            cleanup.migrate();
            assertEquals(0, db.sql("SELECT count(*) FROM information_schema.columns WHERE table_schema = :schema AND column_name = 'data' AND table_name IN ('car_image','manufacturer_logo','series_logo','driver_photo','event_document')")
                    .param("schema", schema).query(Integer.class).single());
            assertEquals(0, db.sql("SELECT count(*) FROM information_schema.tables WHERE table_schema = :schema AND table_name = 'car_image_variant'")
                    .param("schema", schema).query(Integer.class).single());
            assertEquals(4, db.sql("SELECT page FROM event_document_page WHERE document_id = :id").param("id", document).query(Integer.class).single());
            assertTrue(db.sql("SELECT invert_on_dark FROM manufacturer_logo").query(Boolean.class).single());
            connection.setSchema("public");
        } finally {
            JdbcClient.create(source).sql("DROP SCHEMA " + schema + " CASCADE").update();
        }
    }
}
