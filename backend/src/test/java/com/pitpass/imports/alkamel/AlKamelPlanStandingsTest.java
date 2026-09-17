package com.pitpass.imports.alkamel;

import com.pitpass.imports.alkamel.AlKamelCatalog.Kind;
import com.pitpass.imports.alkamel.AlKamelCatalog.SourceFile;
import com.pitpass.imports.alkamel.AlKamelCatalog.Status;
import com.pitpass.imports.alkamel.AlKamelImportService.PlanFile;
import com.pitpass.imports.alkamel.AlKamelImportService.PlanSession;
import com.pitpass.imports.alkamel.AlKamelImportService.PlanWeekend;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Which standings files a plan ticks by default — one copy of each sheet. */
class AlKamelPlanStandingsTest {

    private static SourceFile file(String folder, String name, Status status, int amendment, String modified) {
        return new SourceFile(folder + "/" + name, name, modified, Kind.STANDINGS, status, amendment,
                AlKamelCatalog.extensionOf(name));
    }

    @Test
    void officialAndProvisionalPointsFoldersTickTheOfficialCopyOnly() {
        List<SourceFile> standings = List.of(
                file("Points Data - Offiical", "IWSC 01 GTP Drivers.json", Status.OFFICIAL, 0, "2026-08-28 15:21"),
                file("Points Data - Offiical", "IWSC 02 GTP Teams.json", Status.OFFICIAL, 0, "2026-08-28 15:21"),
                file("Points Data - Offiical", "S01 Jim Trueman Award.json", Status.OFFICIAL, 0, "2026-08-28 15:21"),
                file("Points Data - Provisional", "IWSC 01 GTP Drivers.json", Status.PROVISIONAL, 0, "2026-08-23 21:20"),
                file("Points Data - Provisional", "IWSC 02 GTP Teams.json", Status.PROVISIONAL, 0, "2026-08-23 21:20"),
                file("", "00_Championship Points - Official.pdf", Status.OFFICIAL, 0, "2026-08-28 15:18"),
                file("", "00_Championship Points - Provisional.pdf", Status.PROVISIONAL, 0, "2026-08-23 21:19"));
        List<PlanFile> plan = AlKamelImportService.planStandings(standings);
        assertEquals(List.of("Points Data - Offiical/IWSC 01 GTP Drivers.json", "Points Data - Offiical/IWSC 02 GTP Teams.json"),
                plan.stream().filter(PlanFile::recommended).map(PlanFile::path).toList());
        assertEquals(7, plan.size(), "every copy is still listed for the admin to see");
    }

    @Test
    void withoutJsonOnlyTheBestCopyOfTheMainPointsSheetIsTicked() {
        List<SourceFile> standings = List.of(
                file("00_Points", "00_Championship Points - Revised Official.pdf", Status.OFFICIAL, 1, "2017-02-01 10:00"),
                file("00_Points", "00_Championship Points - Official.pdf", Status.OFFICIAL, 0, "2017-01-30 10:00"),
                file("00_Points", "02_TPNAEC Points - Official.pdf", Status.OFFICIAL, 0, "2017-01-30 10:00"),
                file("", "00_IMEC Championship Points - Official.pdf", Status.OFFICIAL, 0, "2023-10-15 10:00"),
                file("00_Points", "07_VP Fuels Front Runner Award.pdf", Status.UNMARKED, 0, "2017-01-30 10:00"));
        List<PlanFile> plan = AlKamelImportService.planStandings(standings);
        assertEquals(List.of("00_Championship Points - Revised Official.pdf"),
                plan.stream().filter(PlanFile::recommended).map(PlanFile::name).toList());
        // The cup sheets are offered with a reason, not silently dropped.
        assertEquals(2, plan.stream().filter(f -> f.note() != null && f.note().contains("cup sheet")).count());
        assertEquals(5, plan.size());
    }

    @Test
    void theMainSheetIsTheOneWithNoCupCodeInFront() {
        assertEquals(true, AlKamelImportService.isMainPointsSheet("00_Championship Points - Official.pdf"));
        assertEquals(true, AlKamelImportService.isMainPointsSheet("00_Champonship Points - Official.pdf"));
        assertEquals(true, AlKamelImportService.isMainPointsSheet("00_Championship Points - Revised Official.pdf"));
        assertEquals(false, AlKamelImportService.isMainPointsSheet("00_IMEC Championship Points - Official.pdf"));
        assertEquals(false, AlKamelImportService.isMainPointsSheet("00_IMEC MRRA Points - Official.pdf"));
        assertEquals(false, AlKamelImportService.isMainPointsSheet("00_IMEC Sebring 12H Points - Official.pdf"));
    }

    @Test
    void preseasonWeekendsAreKnownByTheirFolderName() {
        assertEquals(true, AlKamelImportService.isPreseason("01_ROAR Before the 24"));
        assertEquals(true, AlKamelImportService.isPreseason("03_Sebring February Test"));
        assertEquals(true, AlKamelImportService.isPreseason("00_Daytona Test"));
        assertEquals(true, AlKamelImportService.isPreseason("06_Sebring Prologue"));
        assertEquals(false, AlKamelImportService.isPreseason("02_Daytona International Speedway"));
        assertEquals(false, AlKamelImportService.isPreseason("07_Sebring International Raceway"));
    }

    @Test
    void endurenceCupCheckpointsAreListedButNotTicked() {
        List<SourceFile> standings = List.of(
                file("Points Data - Official", "01 IWSC GTP Drivers Standings.json", Status.OFFICIAL, 0, "2025-10-12 20:00"),
                file("Points Data - Official", "20 IMEC GTP Drivers Standings Overall.json", Status.OFFICIAL, 0, "2025-10-12 20:00"),
                file("Points Data - Official", "21 IMEC GTP Drivers Standings MRRA.json", Status.OFFICIAL, 0, "2025-10-12 20:00"));
        List<PlanFile> plan = AlKamelImportService.planStandings(standings);
        assertEquals(List.of("01 IWSC GTP Drivers Standings.json", "20 IMEC GTP Drivers Standings Overall.json"),
                plan.stream().filter(PlanFile::recommended).map(PlanFile::name).toList());
        assertEquals("cup checkpoint after one race — the Overall file is the season table",
                plan.stream().filter(f -> f.name().contains("MRRA")).findFirst().orElseThrow().note());
    }

    private static PlanWeekend weekend(String event, long seriesId, boolean loose, String start, boolean withStandings) {
        List<PlanSession> sessions = List.of(new PlanSession(event + "/race/", LocalDateTime.parse(start), "Race", "RACE",
                null, null, null));
        List<PlanFile> standings = withStandings
                ? List.of(new PlanFile(event + "/points.pdf", "00_Championship Points - Official.pdf", "STANDINGS",
                        "IMSA_POINTS_PDF", "OFFICIAL", 0, null, true, null, null))
                : List.of();
        return new PlanWeekend("25_2025/" + event, event + "/", event + "/series/", event, 2025, loose ? null : "01_Series",
                seriesId, "Series", loose, false, false, null, sessions, standings, false, null, null);
    }

    @Test
    void aLooseFolderNeverOutranksTheSeriesRealFinaleForStandings() {
        // Planning one series attributes every folder posted without a series
        // folder to it; Jerez, posted last and carrying its own points sheet,
        // must not take the final-standings mark from Road Atlanta.
        List<PlanWeekend> weekends = new ArrayList<>(List.of(
                weekend("02_Daytona", 1, false, "2025-01-25T13:40", true),
                weekend("19_Road Atlanta", 1, false, "2025-10-11T12:10", true),
                weekend("20_Jerez", 1, true, "2025-11-15T10:00", true)));
        AlKamelImportService.markFinalStandings(weekends);
        assertEquals(List.of("19_Road Atlanta"),
                weekends.stream().filter(PlanWeekend::finalStandings).map(PlanWeekend::eventName).toList());

        // With no proper weekend carrying standings, the latest loose one does.
        List<PlanWeekend> looseOnly = new ArrayList<>(List.of(
                weekend("02_Daytona", 1, false, "2025-01-25T13:40", false),
                weekend("07_COTA", 1, true, "2025-09-06T10:00", true),
                weekend("20_Jerez", 1, true, "2025-11-15T10:00", true)));
        AlKamelImportService.markFinalStandings(looseOnly);
        assertEquals(List.of("20_Jerez"),
                looseOnly.stream().filter(PlanWeekend::finalStandings).map(PlanWeekend::eventName).toList());
    }

    @Test
    void aFolderRepeatingAnEarlierFoldersSessionsIsFlaggedAsADuplicate() {
        // 2025: "17_Circuit of the Americas" and "19_Circuit of the Americas (MC)"
        // hold the same Mustang Challenge sessions; the second is the repeat.
        PlanWeekend first = weekend("17_Circuit of the Americas", 3, false, "2025-09-06T16:40", false);
        PlanWeekend repeat = new PlanWeekend("25_2025/19_Circuit of the Americas (MC)", "19/", "19/series/",
                "Circuit of the Americas (MC)", 2025, "01_Mustang Challenge", 3L, "Mustang Challenge", false, false,
                false, null, first.sessions(), List.of(), false, null, null);
        PlanWeekend other = weekend("18_Charlotte", 3, false, "2025-10-04T14:00", false);
        PlanWeekend otherSeries = new PlanWeekend("25_2025/19_Circuit of the Americas (MC)", "19/", "19/pccna/",
                "Circuit of the Americas (MC)", 2025, "02_PCCNA", 9L, "PCCNA", false, false,
                false, null, first.sessions(), List.of(), false, null, null);
        List<AlKamelImportService.DuplicateWeekend> dupes =
                AlKamelImportService.duplicateWeekends(List.of(first, repeat, other, otherSeries));
        assertEquals(1, dupes.size());
        assertEquals("25_2025/19_Circuit of the Americas (MC)", dupes.get(0).sourceEvent());
        assertEquals("01_Mustang Challenge", dupes.get(0).seriesFolder());
        assertEquals("25_2025/17_Circuit of the Americas", dupes.get(0).duplicateOf());
    }
}
