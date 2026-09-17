package com.pitpass.imports.alkamel;

import com.pitpass.imports.alkamel.AlKamelCatalog.Kind;
import com.pitpass.imports.alkamel.AlKamelCatalog.SourceFile;
import com.pitpass.imports.alkamel.AlKamelCatalog.Status;
import com.pitpass.imports.alkamel.AlKamelImportService.PlanFile;
import org.junit.jupiter.api.Test;

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
}
