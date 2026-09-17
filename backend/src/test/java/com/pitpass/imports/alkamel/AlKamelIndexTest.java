package com.pitpass.imports.alkamel;

import com.pitpass.imports.ImportFormat;
import com.pitpass.imports.alkamel.AlKamelCatalog.Kind;
import com.pitpass.imports.alkamel.AlKamelCatalog.SessionType;
import com.pitpass.imports.alkamel.AlKamelCatalog.SourceFile;
import com.pitpass.imports.alkamel.AlKamelIndex.EventFolder;
import com.pitpass.imports.alkamel.AlKamelIndex.EventListing;
import com.pitpass.imports.alkamel.AlKamelIndex.SeriesContents;
import com.pitpass.imports.alkamel.AlKamelIndex.SessionFiles;
import com.pitpass.imports.alkamel.AlKamelIndex.YearFolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Walks recorded listings from every era the importer has to read: 2017
 * (unmarked files, a points folder, hourly endurance folders), 2021 (class-split
 * qualifying, a plural series name), 2023 (a weekend posted with no series
 * folder), 2025 (a Formula 1 support weekend, PDF + XML only) and 2026 (JSON
 * everywhere, a grids subfolder, a points-data folder).
 */
class AlKamelIndexTest {

    private static final String LB17 = "17_2017/06_Long%20Beach%20Street%20Circuit/";
    private static final String W17 = LB17 + "01_IMSA%20WeatherTech%20SportsCar%20Championship/";
    private static final String DAY17 = "17_2017/02_Daytona%20International%20Speedway/"
            + "01_IMSA%20WeatherTech%20SportsCar%20Championship/201701281430_Race/";
    private static final String W26 = "26_2026/07_Long%20Beach%20Street%20Circuit/"
            + "01_IMSA%20WeatherTech%20SportsCar%20Championship/";
    private static final String MO21 = "21_2021/08_Mid-Ohio%20Sports%20Car%20Course/";
    private static final String W21 = MO21 + "01_IMSA%20WeatherTech%20SportsCar%20Championships/";
    private static final String MI25 = "25_2025/09_Miami%20Grand%20Prix/01_Porsche%20Carrera%20Cup%20North%20America/";
    private static final String COTA23 = "23_2023/22_PCCNA%20COTA/";

    private FixtureSite site;
    private AlKamelIndex index;

    @BeforeEach
    void start() throws IOException {
        site = new FixtureSite();
        index = new AlKamelIndex(site.client());
    }

    @AfterEach
    void stop() {
        site.close();
    }

    @Test
    void yearsAndEventsComeFromTheRootAndYearFolders() {
        List<YearFolder> years = index.years();
        assertEquals(11, years.size());
        assertEquals(2016, years.get(0).year());
        assertEquals(2026, years.get(10).year());
        YearFolder y2017 = index.year(2017).orElseThrow();
        assertEquals("17_2017/", y2017.path());

        List<EventFolder> events = index.events(y2017);
        assertEquals(27, events.size());
        assertEquals("01_ROAR Before the 24", events.get(0).folderName());
        assertEquals("ROAR Before the 24", events.get(0).name());
        assertEquals("17_2017/01_ROAR%20Before%20the%2024/", events.get(0).path());
        assertEquals(2017, events.get(0).year());
    }

    @Test
    void anEventListsItsSeriesFolders() {
        EventListing lb = index.event(LB17);
        assertEquals(1, lb.series().size());
        assertEquals("IMSA WeatherTech SportsCar Championship", lb.series().get(0).name());
        assertEquals(W17, lb.series().get(0).path());
        assertTrue(lb.looseSessions().isEmpty());

        EventListing mo = index.event(MO21);
        assertEquals(List.of("IMSA WeatherTech SportsCar Championships", "IMSA Michelin Pilot SportsCar Challenge",
                        "IMSA Prototype Challenge", "Idemitsu Mazda MX-5 Cup presented by BFGoodrich Tires"),
                mo.series().stream().map(AlKamelIndex.SeriesFolder::name).toList());
    }

    @Test
    void aWeekendPostedWithoutASeriesFolderSurfacesItsSessionsLoose() {
        EventListing cota = index.event(COTA23);
        assertTrue(cota.series().isEmpty());
        assertEquals(List.of("Practice 1", "Qualifying", "Race 1", "Race 2"),
                cota.looseSessions().stream().map(s -> s.folder().label()).toList());
        // Read as a series folder, the same event yields its standings too.
        SeriesContents contents = index.series(COTA23);
        assertEquals(4, contents.sessions().size());
        assertEquals(2, contents.standings().size());
    }

    @Test
    void aSeriesFolderYieldsSessionsInStartOrderAndItsStandings() {
        SeriesContents w17 = index.series(W17);
        assertEquals(List.of("Practice 1", "Practice 2", "Qualifying", "Race"),
                w17.sessions().stream().map(s -> s.folder().label()).toList());
        assertEquals(LocalDateTime.of(2017, 4, 8, 13, 5), w17.sessions().get(3).folder().start());
        assertEquals(W17 + "201704081305_Race/", w17.sessions().get(3).path());
        // 2017 keeps the points sheet in 00_Points/, whose own subfolder is skipped.
        assertEquals(1, w17.standings().size());
        assertEquals("00_Champonship Points - Official.pdf", w17.standings().get(0).name());
        assertEquals(W17 + "00_Points/00_Champonship%20Points%20-%20Official.pdf", w17.standings().get(0).path());
        assertTrue(w17.entryLists().isEmpty());
    }

    @Test
    void classSplitQualifyingSessionsEachKeepTheirLabel() {
        SeriesContents w21 = index.series(W21);
        assertEquals(List.of("Practice 1", "Practice 2", "Qualifying - GTD Position", "Qualifying - GTD Points",
                        "Qualifying - LMP3 Position - Points", "Qualifying - DPi Position - Points", "Warm Up", "Race"),
                w21.sessions().stream().map(s -> s.folder().label()).toList());
        assertEquals(4, w21.sessions().stream().filter(s -> s.folder().type() == SessionType.QUALIFYING).count());
        // The Sprint Cup and award sheets beside the points PDFs are not standings the importer reads.
        assertEquals(List.of("00_Championship Points - Official.pdf", "00_Championship Points - Provisional.pdf"),
                w21.standings().stream().map(SourceFile::name).toList());

        SessionFiles q = index.session(W21 + "202105151220_Qualifying%20-%20GTD%20Position/");
        assertEquals("03_Results_Qualifying - GTD Position.CSV", q.best(Kind.RESULTS, false).orElseThrow().name());
    }

    @Test
    void from2024StandingsAlsoComeAsAPointsDataFolder() {
        SeriesContents w26 = index.series(W26);
        assertEquals(4, w26.sessions().size());
        long pdfs = w26.standings().stream().filter(f -> "PDF".equals(f.extension())).count();
        long jsons = w26.standings().stream().filter(f -> "JSON".equals(f.extension())).count();
        assertEquals(2, pdfs);
        assertEquals(18, jsons);
        assertTrue(w26.standings().stream().anyMatch(f -> f.name().equals("IWSC 01 GTP Drivers.json")));
        assertTrue(w26.standings().stream().allMatch(f -> f.kind() == Kind.STANDINGS));
    }

    @Test
    void anEmptyFileOnTheSiteIsNeverTheChosenCopy() {
        // 2023 Sebring: the Official grid CSV was posted at 0 bytes; the amended
        // Provisional beside it is the real grid.
        SessionFiles race = index.session("23_2023/07_Sebring%20International%20Raceway/"
                + "01_IMSA%20WeatherTech%20SportsCar%20Championship/202303181010_Race/");
        List<SourceFile> grids = race.of(Kind.GRID);
        SourceFile official = grids.stream().filter(f -> f.name().equals("00_Grid_Race_Official.CSV")).findFirst().orElseThrow();
        assertTrue(official.empty());
        assertEquals("00_Grid_Race_Provisional_Amended.CSV", race.best(Kind.GRID, false).orElseThrow().name());
        assertEquals(1, race.best(Kind.GRID, false).orElseThrow().amendment());
    }

    @Test
    void pointsFoldersLendTheirStatusToTheFilesInside() {
        // 2026 VIR: "Points Data - Offiical/" (sic) and "Points Data - Provisional/"
        // hold identically named championship JSONs; the folder says which is which.
        SeriesContents vir = index.series("26_2026/18_VIRginia%20International%20Raceway/"
                + "01_IMSA%20WeatherTech%20SportsCar%20Championship/");
        List<SourceFile> gtpDrivers = vir.standings().stream()
                .filter(f -> f.name().equals("IWSC 01 GTP Drivers.json")).toList();
        assertEquals(2, gtpDrivers.size());
        assertEquals(List.of(AlKamelCatalog.Status.OFFICIAL, AlKamelCatalog.Status.PROVISIONAL),
                gtpDrivers.stream().map(SourceFile::status).sorted().toList());
        SourceFile best = AlKamelCatalog.best(gtpDrivers, Kind.STANDINGS, false).orElseThrow();
        assertEquals(AlKamelCatalog.Status.OFFICIAL, best.status());
        assertTrue(best.path().contains("Offiical"));
    }

    @Test
    void aSprintSessionIn2017HasUnmarkedCsvResultsAndAPdfGrid() {
        SessionFiles race = index.session(W17 + "201704081305_Race/");
        assertEquals(3, race.of(Kind.RESULTS).size());
        SourceFile results = race.best(Kind.RESULTS, false).orElseThrow();
        assertEquals("05_Results.CSV", results.name());
        assertEquals(ImportFormat.IMSA_CSV, AlKamelCatalog.formatFor(Kind.RESULTS, results.extension(), false).orElseThrow());
        SourceFile grid = race.best(Kind.GRID, false).orElseThrow();
        assertEquals("01_Starting Grid.PDF", grid.name());
        assertEquals(ImportFormat.IMSA_GRID_PDF, AlKamelCatalog.formatFor(Kind.GRID, grid.extension(), false).orElseThrow());
        assertTrue(race.of(Kind.FLAGS).isEmpty());
        assertTrue(race.of(Kind.ENTRY_LIST).isEmpty());
    }

    @Test
    void anEnduranceRaceReadsItsResultsFromTheLastHourFolderOnly() {
        SessionFiles daytona = index.session(DAY17);
        SourceFile results = daytona.best(Kind.RESULTS, false).orElseThrow();
        assertEquals("05_Results by Hour.CSV", results.name());
        assertEquals(DAY17 + "Hour%2024/05_Results%20by%20Hour.CSV", results.path());
        assertEquals("03_Starting Grid.PDF", daytona.best(Kind.GRID, false).orElseThrow().name());
        assertEquals(3, daytona.of(Kind.GRID).size(), "side-by-side sheet skipped");
        assertEquals("02_Pre-Race Entry List.pdf", daytona.best(Kind.ENTRY_LIST, false).orElseThrow().name());
        // Only the session folder and Hour 24 were listed — not the other 23 hours.
        assertEquals(2, site.requests.size(), site.requests.toString());
        assertTrue(site.requests.get(1).endsWith("Hour%2024/"));
    }

    @Test
    void from2024GridsLiveInASubfolderAndEverythingHasJson() {
        SessionFiles race = index.session(W26 + "202604181305_Race/");
        assertEquals("03_Results_Race_Official.JSON", race.best(Kind.RESULTS, false).orElseThrow().name());
        SourceFile grid = race.best(Kind.GRID, false).orElseThrow();
        assertEquals("01_Starting Grid SbS_Race_Official.JSON", grid.name());
        assertEquals(W26 + "202604181305_Race/00_Starting%20Grids/01_Starting%20Grid%20SbS_Race_Official.JSON", grid.path());
        assertEquals("25_FlagsAnalysisWithRCMessages_Race.JSON", race.best(Kind.FLAGS, false).orElseThrow().name());
        assertEquals(9, race.of(Kind.RESULTS).size(), "three statuses in three formats");
    }

    @Test
    void aFormulaOneSupportWeekendOnlyImportsAsF1Pdf() {
        SessionFiles race1 = index.session(MI25 + "202505031750_Race%201/");
        assertTrue(race1.best(Kind.RESULTS, false).isEmpty());
        SourceFile results = race1.best(Kind.RESULTS, true).orElseThrow();
        assertEquals("03_Results_Race 1_Official.pdf", results.name());
        assertEquals(ImportFormat.F1_PDF, AlKamelCatalog.formatFor(Kind.RESULTS, results.extension(), true).orElseThrow());
        assertEquals("01_Grid_Race 1_Official.pdf", race1.best(Kind.GRID, true).orElseThrow().name());
        assertEquals(ImportFormat.F1_PDF, AlKamelCatalog.formatFor(Kind.GRID, "PDF", true).orElseThrow());

        // Standings: a "POINTS DATA" folder (no status suffix that year) of JSON per
        // championship beside the PDFs — the JSON wins, and among the PDFs the revised one.
        SeriesContents miami = index.series(MI25);
        assertEquals("JSON", AlKamelCatalog.best(miami.standings(), Kind.STANDINGS, true).orElseThrow().extension());
        assertEquals(5, miami.standings().stream().filter(f -> "JSON".equals(f.extension())).count());
        List<SourceFile> pdfs = miami.standings().stream().filter(f -> "PDF".equals(f.extension())).toList();
        assertEquals("00_Championship Points - Official REVISED.pdf",
                AlKamelCatalog.best(pdfs, Kind.STANDINGS, true).orElseThrow().name());
    }

    @Test
    void freshReadsBypassTheListingCache() {
        index.session(W17 + "201704081305_Race/");
        index.session(W17 + "201704081305_Race/");
        assertEquals(1, site.requests.size());
        index.session(W17 + "201704081305_Race/", true);
        assertEquals(2, site.requests.size());
    }
}
