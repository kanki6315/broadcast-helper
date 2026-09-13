package com.pitpass.documents;

import com.pitpass.images.PublicImageStorage;
import com.pitpass.sheets.SheetController;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@Transactional
class PublicDocumentStorageTest {
    @Autowired JdbcClient db;
    @Autowired StorylineController storylines;
    @Autowired EventDocumentController teams;
    @Autowired PitAssignmentController pits;
    @Autowired DocumentStorage documents;
    @Autowired SheetController sheets;
    @MockitoBean PublicImageStorage storage;
    long event;
    final byte[] pdf = "%PDF-1.4\nfixture".getBytes();
    final List<Path> temporaryFiles = new ArrayList<>();

    @BeforeEach void setup() {
        when(storage.enabled()).thenReturn(true);
        when(storage.publicUrl(anyString())).thenAnswer(i -> URI.create("https://images.example/" + i.getArgument(0)));
        doAnswer(i -> {
            Path path = i.getArgument(1); temporaryFiles.add(path);
            assertArrayEquals(pdf, Files.readAllBytes(path));
            return null;
        }).when(storage).uploadPdf(anyString(), any(Path.class));
        long series = db.sql("INSERT INTO series(name) VALUES (:name) RETURNING id").param("name", UUID.randomUUID().toString()).query(Long.class).single();
        long season = db.sql("INSERT INTO season(series_id,year) VALUES (:id,2026) RETURNING id").param("id", series).query(Long.class).single();
        event = db.sql("INSERT INTO event(season_id,name) VALUES (:id,'PDF') RETURNING id").param("id", season).query(Long.class).single();
    }
    @AfterEach void cleanupVerified() { for (Path path : temporaryFiles) assertFalse(Files.exists(path)); }

    @Test void newPdfUsesDiskAndRemoteKeyAndDirectSheetUrl() {
        var file = new MockMultipartFile("file", "notes.pdf", "application/pdf", pdf) {
            @Override public byte[] getBytes() { throw new AssertionError("Upload must not buffer the PDF in Java"); }
        };
        var result = storylines.upload(event, file);
        assertTrue(result.documentUrl().startsWith("https://images.example/documents/"));
        assertEquals(result.documentUrl(), sheets.sheet(event).storylinesUrl());
        assertTrue(db.sql("SELECT object_key IS NOT NULL FROM event_document WHERE event_id = :id").param("id", event).query(Boolean.class).single());
        assertEquals(URI.create(result.documentUrl()), storylines.data(event, null).getHeaders().getLocation());
        assertNull(storylines.data(event, null).getBody());
        var replacement = storylines.upload(event, file);
        assertNotEquals(result.documentUrl(), replacement.documentUrl());
    }

    @Test void parsedPdfsReadTemporaryPathsAndPreserveTheirExtractedMetadata() throws Exception {
        Path script = Files.createTempFile("pdf-parser-fixture-", ".py");
        try {
            Files.writeString(script, "import sys, pathlib\nassert pathlib.Path(sys.argv[1]).read_bytes().startswith(b'%PDF')\nprint('{\"page_count\":7,\"cars\":[{\"car_number\":\"23\",\"page\":3}]}')\n");
            var teamController = new EventDocumentController(db, new com.fasterxml.jackson.databind.ObjectMapper(), documents, "python3", script.toString());
            var result = teamController.upload(event, new MockMultipartFile("file", "teams.pdf", "application/pdf", pdf));
            assertEquals(7, result.pageCount());
            assertEquals(3, result.pages().getFirst().page());
            assertTrue(result.documentUrl().startsWith("https://images.example/documents/"));
            db.sql("INSERT INTO entry(event_id,car_number,class_name,team_name) VALUES (:event,'23','GTP','Test')").param("event", event).update();
            Files.writeString(script, "import sys, pathlib\nassert pathlib.Path(sys.argv[1]).read_bytes().startswith(b'%PDF')\nprint('{\"series\":[\"IWSC\"],\"boxes\":[{\"box\":1,\"cars\":{\"IWSC\":{\"car_number\":\"23\",\"team\":\"Test\"}}}],\"version_note\":\"V2\"}')\n");
            var pitController = new PitAssignmentController(db, new com.fasterxml.jackson.databind.ObjectMapper(), documents, "python3", script.toString());
            var proposal = pitController.upload(event, new MockMultipartFile("file", "pits.pdf", "application/pdf", pdf));
            assertEquals("IWSC", proposal.seriesColumn());
            assertEquals("23", proposal.rows().getFirst().carNumber());
            assertEquals("V2", pitController.get(event).versionNote());
            assertEquals(2, db.sql("SELECT count(*) FROM event_document WHERE event_id = :id AND object_key IS NOT NULL").param("id", event).query(Integer.class).single());
        } finally { Files.deleteIfExists(script); }
    }

    @Test void failedUploadKeepsExistingDocumentAndCleansTemporaryFile() {
        var original = storylines.upload(event, new MockMultipartFile("file", "notes.pdf", "application/pdf", pdf));
        doAnswer(i -> { temporaryFiles.add(i.getArgument(1)); throw new ResponseStatusException(HttpStatus.BAD_GATEWAY); })
                .when(storage).uploadPdf(anyString(), any(Path.class));
        assertThrows(ResponseStatusException.class, () -> storylines.upload(event, new MockMultipartFile("file", "new.pdf", "application/pdf", pdf)));
        assertEquals(original, storylines.get(event));
        assertThrows(ResponseStatusException.class, () -> documents.receive(new MockMultipartFile("file", "fake.pdf", "application/pdf", new byte[8])));
    }
}
