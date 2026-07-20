package com.pitpass.imports;

import com.pitpass.imports.ImportService.GroupBatch;
import com.pitpass.imports.ImportService.GroupCommitRequest;
import com.pitpass.imports.ImportService.ImportTarget;
import com.pitpass.imports.ImportService.ProposedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pure-logic checks for the group-commit request handling — the validation and
 * partitioning that runs before any database work. The DB-touching orchestration
 * (transactions, event creation, renumbering) is covered by the manual browser
 * pass, since the suite has no database.
 */
class GroupCommitRequestTest {

    private static ImportTarget target(Long seriesId) {
        return new ImportTarget(seriesId, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static GroupCommitRequest req(List<ProposedEvent> events, List<GroupBatch> batches) {
        return new GroupCommitRequest(events, batches);
    }

    @Test
    void chosenEventNamePrefersOverrideThenPayload() {
        assertEquals("Rolex 24", ImportService.chosenEventName(
                new ImportTarget(1L, null, null, "Rolex 24", null, null, null, null, null, null, null, null),
                "Daytona"));
        assertEquals("Daytona", ImportService.chosenEventName(target(1L), "Daytona"));
        // Blank override falls through to the payload name.
        assertEquals("Daytona", ImportService.chosenEventName(
                new ImportTarget(1L, null, null, "  ", null, null, null, null, null, null, null, null),
                "Daytona"));
    }

    @Test
    void validateAcceptsAWellFormedRequest() {
        Map<String, ProposedEvent> byKey = ImportService.validateGroupRequest(req(
                List.of(new ProposedEvent("g1", null, "Road Atlanta", "2026-01-22"),
                        new ProposedEvent("attach-5", 5L, "Sebring", "2026-03-19")),
                List.of(new GroupBatch(10, "g1", target(1L)),
                        new GroupBatch(11, "g1", target(1L)),
                        new GroupBatch(12, "attach-5", target(1L)),
                        new GroupBatch(13, null, target(1L))))); // a standings batch
        assertEquals(2, byKey.size());
        assertEquals("g1", byKey.get("g1").key());
    }

    @Test
    void validateRejectsUnknownEventKey() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                ImportService.validateGroupRequest(req(
                        List.of(new ProposedEvent("g1", null, "Road Atlanta", "2026-01-22")),
                        List.of(new GroupBatch(10, "ghost", target(1L))))));
        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void validateRejectsBlankCreateName() {
        assertThrows(ResponseStatusException.class, () ->
                ImportService.validateGroupRequest(req(
                        List.of(new ProposedEvent("g1", null, "  ", "2026-01-22")),
                        List.of(new GroupBatch(10, "g1", target(1L))))));
    }

    @Test
    void validateRejectsDuplicateCreateNames() {
        // Case-insensitive: two new events with the same name would collide on commit.
        assertThrows(ResponseStatusException.class, () ->
                ImportService.validateGroupRequest(req(
                        List.of(new ProposedEvent("g1", null, "Road Atlanta", "2026-01-22"),
                                new ProposedEvent("g2", null, "road atlanta", "2026-07-01")),
                        List.of(new GroupBatch(10, "g1", target(1L)),
                                new GroupBatch(11, "g2", target(1L))))));
    }

    @Test
    void validateRejectsDuplicateEventKey() {
        assertThrows(ResponseStatusException.class, () ->
                ImportService.validateGroupRequest(req(
                        List.of(new ProposedEvent("g1", null, "Road Atlanta", "2026-01-22"),
                                new ProposedEvent("g1", null, "Sebring", "2026-03-19")),
                        List.of())));
    }

    @Test
    void groupByEventKeyPartitionsInOrderAndKeepsStandingsSeparate() {
        Map<String, List<GroupBatch>> grouped = ImportService.groupByEventKey(List.of(
                new GroupBatch(10, "g1", target(1L)),
                new GroupBatch(11, null, target(1L)),   // standings
                new GroupBatch(12, "g1", target(1L)),
                new GroupBatch(13, "g2", target(1L))));
        assertEquals(List.of(10L, 12L), grouped.get("g1").stream().map(GroupBatch::batchId).toList());
        assertEquals(List.of(13L), grouped.get("g2").stream().map(GroupBatch::batchId).toList());
        assertEquals(List.of(11L), grouped.get(null).stream().map(GroupBatch::batchId).toList());
        // Insertion order preserved: g1 before g2.
        assertEquals(List.of("g1", "g2"), grouped.keySet().stream().filter(k -> k != null).toList());
    }
}
