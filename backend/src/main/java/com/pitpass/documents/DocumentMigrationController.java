package com.pitpass.documents;

import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/document-storage")
public class DocumentMigrationController {
    private final DocumentStorage storage;
    public DocumentMigrationController(DocumentStorage storage) { this.storage = storage; }
    @GetMapping("/legacy")
    public List<DocumentStorage.LegacyDocument> legacy(@RequestParam long eventId) { return storage.legacy(eventId); }
    @PostMapping("/{id}/migrate")
    public void migrate(@PathVariable long id) { storage.migrate(id); }
}
