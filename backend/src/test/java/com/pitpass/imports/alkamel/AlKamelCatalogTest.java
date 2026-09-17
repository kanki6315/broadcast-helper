package com.pitpass.imports.alkamel;

import com.pitpass.imports.ImportFormat;
import com.pitpass.imports.alkamel.AlKamelCatalog.Kind;
import com.pitpass.imports.alkamel.AlKamelCatalog.Revision;
import com.pitpass.imports.alkamel.AlKamelCatalog.SessionFolder;
import com.pitpass.imports.alkamel.AlKamelCatalog.SessionType;
import com.pitpass.imports.alkamel.AlKamelCatalog.SourceFile;
import com.pitpass.imports.alkamel.AlKamelCatalog.Status;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The site's naming rules, checked against names copied from real listings. */
class AlKamelCatalogTest {

    @Test
    void parsesAnApacheIndexIntoRowsSkippingSortAndParentLinks() throws IOException {
        List<IndexEntry> rows = AlKamelCatalog.parseListing(FixtureSite.text("year-2017.html"));
        List<IndexEntry> dirs = rows.stream().filter(IndexEntry::directory).toList();
        assertEquals(27, dirs.size());
        IndexEntry first = dirs.get(0);
        assertEquals("01_ROAR%20Before%20the%2024/", first.href());
        assertEquals("01_ROAR Before the 24", first.name());
        assertEquals(LocalDateTime.of(2017, 1, 27, 0, 49).getYear(), first.modifiedAt().getYear());
        assertTrue(rows.stream().noneMatch(r -> r.name().contains("Parent")));
        assertTrue(rows.stream().noneMatch(r -> r.href().startsWith("?")));
    }

    @Test
    void rootListsYearsAndOneStrayFile() throws IOException {
        List<IndexEntry> rows = AlKamelCatalog.parseListing(FixtureSite.text("root.html"));
        assertEquals(11, rows.stream().filter(IndexEntry::directory).count());
        assertEquals(2016, AlKamelCatalog.yearOf(rows.get(0).name()).getAsInt());
        assertTrue(AlKamelCatalog.yearOf("intranetFtpNames.cfg").isEmpty());
    }

    @Test
    void folderNamesDropTheirPostingNumber() {
        assertEquals("Long Beach Street Circuit", AlKamelCatalog.displayName("06_Long Beach Street Circuit"));
        assertEquals("Hour 24", AlKamelCatalog.displayName("Hour 24"));
        assertEquals("IMSA WeatherTech SportsCar Championships",
                AlKamelCatalog.displayName("01_IMSA WeatherTech SportsCar Championships"));
    }

    @Test
    void sessionFoldersCarryStartLabelAndType() {
        SessionFolder q = AlKamelCatalog.parseSessionFolder("202105151220_Qualifying - GTD Position").orElseThrow();
        assertEquals(LocalDateTime.of(2021, 5, 15, 12, 20), q.start());
        assertEquals("Qualifying - GTD Position", q.label());
        assertEquals(SessionType.QUALIFYING, q.type());

        assertEquals(SessionType.PRACTICE,
                AlKamelCatalog.parseSessionFolder("202105160900_Warm Up").orElseThrow().type());
        assertEquals(SessionType.RACE,
                AlKamelCatalog.parseSessionFolder("201701281430_Race").orElseThrow().type());
        assertEquals(SessionType.RACE,
                AlKamelCatalog.parseSessionFolder("202505041015_Race 2").orElseThrow().type());
        assertTrue(AlKamelCatalog.parseSessionFolder("00_Points").isEmpty());
        assertTrue(AlKamelCatalog.parseSessionFolder("POINTS DATA - Official").isEmpty());
    }

    @Test
    void hourFoldersAreNumberedByTheirLabelNotTheirPrefix() {
        assertEquals(1, AlKamelCatalog.hourOf("04_Hour 1").getAsInt());
        assertEquals(9, AlKamelCatalog.hourOf("09_Hour 9").getAsInt());
        assertEquals(24, AlKamelCatalog.hourOf("Hour 24").getAsInt());
        assertTrue(AlKamelCatalog.hourOf("RMon CSV and Replay").isEmpty());
        assertTrue(AlKamelCatalog.hourOf("00_Starting Grids").isEmpty());
    }

    @Test
    void sessionFilesAreClassifiedByKind() {
        assertEquals(Optional.of(Kind.RESULTS), AlKamelCatalog.classifySessionFile("03_Results_Race_Official.JSON"));
        assertEquals(Optional.of(Kind.RESULTS), AlKamelCatalog.classifySessionFile("05_Results.CSV"));
        assertEquals(Optional.of(Kind.RESULTS), AlKamelCatalog.classifySessionFile("05_Results by Hour.CSV"));
        assertEquals(Optional.of(Kind.RESULTS),
                AlKamelCatalog.classifySessionFile("03_Results_Qualifying - GTD Position.CSV"));
        assertEquals(Optional.empty(), AlKamelCatalog.classifySessionFile("05_Results by Class_Race_Official.JSON"));
        assertEquals(Optional.empty(), AlKamelCatalog.classifySessionFile("03_Results by 2nd Fastest Lap_Qualifying.CSV"));
        assertEquals(Optional.empty(), AlKamelCatalog.classifySessionFile("07_Results by Driver Fastest Lap after 3S.PDF"));

        assertEquals(Optional.of(Kind.GRID), AlKamelCatalog.classifySessionFile("02_Grid_Race_Official.CSV"));
        assertEquals(Optional.of(Kind.GRID), AlKamelCatalog.classifySessionFile("01_Starting Grid.PDF"));
        assertEquals(Optional.of(Kind.GRID), AlKamelCatalog.classifySessionFile("03_Provisional REVISED Starting Grid.PDF"));
        // 2026's only grid JSON is named after the side-by-side sheet; the PDF of it is a print layout.
        assertEquals(Optional.of(Kind.GRID), AlKamelCatalog.classifySessionFile("01_Starting Grid SbS_Race_Official.JSON"));
        assertEquals(Optional.empty(), AlKamelCatalog.classifySessionFile("01_Starting Grid SbS_Race_Official.PDF"));
        assertEquals(Optional.empty(), AlKamelCatalog.classifySessionFile("03_Side by Side Grid.PDF"));
        assertEquals(Optional.empty(), AlKamelCatalog.classifySessionFile("02_Starting Grid by Number_Race_Official.PDF"));

        assertEquals(Optional.of(Kind.FLAGS), AlKamelCatalog.classifySessionFile("25_FlagsAnalysisWithRCMessages_Race.JSON"));
        assertEquals(Optional.of(Kind.ENTRY_LIST), AlKamelCatalog.classifySessionFile("02_Pre-Race Entry List.pdf"));

        assertEquals(Optional.empty(), AlKamelCatalog.classifySessionFile("12_Lap Chart_Race.JSON"));
        assertEquals(Optional.empty(), AlKamelCatalog.classifySessionFile("23_Time Cards_Race.CSV"));
        assertEquals(Optional.empty(), AlKamelCatalog.classifySessionFile("07_ Driver Fastest Lap.PDF"));
    }

    @Test
    void seriesLevelFilesAreStandingsOrEntryLists() {
        assertEquals(Optional.of(Kind.STANDINGS), AlKamelCatalog.classifySeriesFile("00_Championship Points - Official.pdf"));
        // 2017's real spelling.
        assertEquals(Optional.of(Kind.STANDINGS), AlKamelCatalog.classifySeriesFile("00_Champonship Points - Official.pdf"));
        assertEquals(Optional.of(Kind.ENTRY_LIST), AlKamelCatalog.classifySeriesFile("IWSC Entry List - Sebring.pdf"));
        assertEquals(Optional.empty(), AlKamelCatalog.classifySeriesFile("02_IMSA WeatherTech Sprint Cup - Official.pdf"));
        assertEquals(Optional.empty(), AlKamelCatalog.classifySeriesFile("05_Trueman-Akin Award - Official.pdf"));
        assertEquals(Optional.empty(), AlKamelCatalog.classifySeriesFile("00_Track Maps.pdf"));
    }

    @Test
    void statusAndAmendmentComeFromTheName() {
        assertEquals(new Revision(Status.OFFICIAL, 0), AlKamelCatalog.revisionOf("03_Results_Race_Official.CSV"));
        assertEquals(new Revision(Status.OFFICIAL, 2), AlKamelCatalog.revisionOf("00_Grid_Race 2_Official_Amended 2.CSV"));
        assertEquals(new Revision(Status.OFFICIAL, 1), AlKamelCatalog.revisionOf("00_Championship Points - Official REVISED.pdf"));
        assertEquals(new Revision(Status.PROVISIONAL, 1), AlKamelCatalog.revisionOf("03_Provisional REVISED Starting Grid.PDF"));
        assertEquals(new Revision(Status.UNOFFICIAL, 0), AlKamelCatalog.revisionOf("03_Results_Race 1_Unofficial.xml"));
        assertEquals(new Revision(Status.UNMARKED, 0), AlKamelCatalog.revisionOf("05_Results.CSV"));
    }

    private static SourceFile file(String name, Kind kind, String modified) {
        return SourceFile.of("x/" + name, new IndexEntry(name, name, false, modified), kind);
    }

    @Test
    void bestFilePrefersMachineFormatThenStatusThenAmendmentThenNewest() {
        List<SourceFile> results2026 = List.of(
                file("03_Results_Race_Official.CSV", Kind.RESULTS, "2026-04-24 14:49"),
                file("03_Results_Race_Official.JSON", Kind.RESULTS, "2026-04-24 14:49"),
                file("03_Results_Race_Official.PDF", Kind.RESULTS, "2026-04-24 14:49"),
                file("03_Results_Race_Provisional.JSON", Kind.RESULTS, "2026-04-19 00:46"),
                file("03_Results_Race_Unofficial.JSON", Kind.RESULTS, "2026-04-18 21:54"));
        assertEquals("03_Results_Race_Official.JSON",
                AlKamelCatalog.best(results2026, Kind.RESULTS, false).orElseThrow().name());

        // 2017 Daytona: the unmarked grid is the final one, posted after the revised provisional.
        List<SourceFile> grids2017 = List.of(
                file("03_Provisional REVISED Starting Grid.PDF", Kind.GRID, "2017-01-28 18:02"),
                file("03_Provisional Starting Grid.PDF", Kind.GRID, "2017-01-27 05:27"),
                file("03_Starting Grid.PDF", Kind.GRID, "2017-01-28 20:11"));
        assertEquals("03_Starting Grid.PDF", AlKamelCatalog.best(grids2017, Kind.GRID, false).orElseThrow().name());

        List<SourceFile> amended = List.of(
                file("00_Grid_Race 2_Official.CSV", Kind.GRID, "2026-06-01 10:00"),
                file("00_Grid_Race 2_Official_Amended 2.CSV", Kind.GRID, "2026-06-01 09:00"),
                file("00_Grid_Race 2_Official_Amended 1.CSV", Kind.GRID, "2026-06-01 09:30"));
        assertEquals("00_Grid_Race 2_Official_Amended 2.CSV",
                AlKamelCatalog.best(amended, Kind.GRID, false).orElseThrow().name());

        // A Provisional JSON outranks an Official CSV: same reader, and the refresh re-imports later.
        List<SourceFile> mixed = List.of(
                file("03_Results_Race_Official.CSV", Kind.RESULTS, "2026-04-24 14:49"),
                file("03_Results_Race_Provisional.JSON", Kind.RESULTS, "2026-04-19 00:46"));
        assertEquals("03_Results_Race_Provisional.JSON",
                AlKamelCatalog.best(mixed, Kind.RESULTS, false).orElseThrow().name());
    }

    @Test
    void pdfOnlyResultsAreImportableOnlyOnAFormulaOneWeekend() {
        List<SourceFile> miami = List.of(
                file("03_Results_Race 1_Official.pdf", Kind.RESULTS, "2025-05-09 20:20"),
                file("03_Results_Race 1_Unofficial.xml", Kind.RESULTS, "2025-05-03 23:50"));
        assertTrue(AlKamelCatalog.best(miami, Kind.RESULTS, false).isEmpty());
        SourceFile best = AlKamelCatalog.best(miami, Kind.RESULTS, true).orElseThrow();
        assertEquals("03_Results_Race 1_Official.pdf", best.name());
        assertEquals(Optional.of(ImportFormat.F1_PDF), AlKamelCatalog.formatFor(Kind.RESULTS, "PDF", true));
    }

    @Test
    void eachKindAndExtensionMapsToOneParserFamily() {
        assertEquals(Optional.of(ImportFormat.IMSA_JSON), AlKamelCatalog.formatFor(Kind.RESULTS, "JSON", false));
        assertEquals(Optional.of(ImportFormat.IMSA_CSV), AlKamelCatalog.formatFor(Kind.RESULTS, "CSV", false));
        assertEquals(Optional.empty(), AlKamelCatalog.formatFor(Kind.RESULTS, "PDF", false));
        assertEquals(Optional.empty(), AlKamelCatalog.formatFor(Kind.RESULTS, "XML", true));
        assertEquals(Optional.of(ImportFormat.IMSA_CSV), AlKamelCatalog.formatFor(Kind.GRID, "CSV", false));
        assertEquals(Optional.of(ImportFormat.IMSA_GRID_PDF), AlKamelCatalog.formatFor(Kind.GRID, "PDF", false));
        assertEquals(Optional.of(ImportFormat.F1_PDF), AlKamelCatalog.formatFor(Kind.GRID, "PDF", true));
        assertEquals(Optional.of(ImportFormat.IMSA_JSON), AlKamelCatalog.formatFor(Kind.FLAGS, "JSON", false));
        assertEquals(Optional.empty(), AlKamelCatalog.formatFor(Kind.FLAGS, "PDF", false));
        assertEquals(Optional.of(ImportFormat.IMSA_JSON), AlKamelCatalog.formatFor(Kind.STANDINGS, "JSON", false));
        assertEquals(Optional.of(ImportFormat.IMSA_POINTS_PDF), AlKamelCatalog.formatFor(Kind.STANDINGS, "PDF", false));
        assertEquals(Optional.empty(), AlKamelCatalog.formatFor(Kind.STANDINGS, "CSV", false));
        assertEquals(Optional.of(ImportFormat.IMSA_PDF), AlKamelCatalog.formatFor(Kind.ENTRY_LIST, "PDF", false));
        assertFalse(AlKamelCatalog.importableExtensions(Kind.ENTRY_LIST, false).contains("JSON"));
    }
}
