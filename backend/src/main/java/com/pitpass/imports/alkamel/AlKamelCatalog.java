package com.pitpass.imports.alkamel;

import com.pitpass.imports.ImportFormat;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rules for reading the Al Kamel IMSA results site, an open Apache index at
 * {@code https://imsa.results.alkamelcloud.com/Results/}. Pure functions over
 * folder and file names; {@link AlKamelClient} fetches and {@link AlKamelIndex}
 * walks. Ported from the 2026-09-16 coverage crawl and verified against its
 * listings (see the {@code fixtures/alkamel} test resources).
 *
 * Path grammar: {@code YY_YYYY/NN_Event/NN_Series/YYYYMMDDHHMM_Session/file}.
 * Event folder numbers are NOT rounds — tests, the Roar and one-series weekends
 * all take a number. Standings sit beside the sessions at series level (a
 * "Championship Points" PDF; from 2024 a {@code POINTS DATA} folder of JSON; in
 * 2017 a {@code 00_Points/} folder). Endurance results live in the last
 * {@code Hour N/} subfolder; 2024+ grids in {@code 00_Starting Grids/}.
 */
public final class AlKamelCatalog {

    private AlKamelCatalog() {
    }

    // ------------------------------------------------------------- listings

    /** A folder or file row: the href, its text, then the "Last modified" cell.
     *  Sort links ("?C=N;O=D") and the parent link ("/Results/") are skipped
     *  by the leading-character class. */
    private static final Pattern ROW = Pattern.compile(
            "<a href=\"([^\"?/][^\"]*)\">[^<]*</a></td><td align=\"right\">([0-9: -]+?)\\s*<");

    /** The rows of an Apache index page, in the order served. */
    public static List<IndexEntry> parseListing(String html) {
        List<IndexEntry> out = new ArrayList<>();
        Matcher m = ROW.matcher(html);
        while (m.find()) {
            out.add(IndexEntry.of(m.group(1), m.group(2).trim()));
        }
        return out;
    }

    // -------------------------------------------------------------- folders

    private static final Pattern YEAR_FOLDER = Pattern.compile("^(\\d{2})_(\\d{4})$");
    private static final Pattern ORDINAL_PREFIX = Pattern.compile("^\\d+_");
    private static final Pattern SESSION_FOLDER = Pattern.compile("^(\\d{12})_(.+)$");
    private static final DateTimeFormatter SESSION_STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final Pattern HOUR_FOLDER = Pattern.compile("(?i)^(?:\\d+_)?hour\\s*(\\d+)$");

    /** "17_2017" → 2017; anything else is empty. */
    public static OptionalInt yearOf(String folderName) {
        Matcher m = YEAR_FOLDER.matcher(folderName);
        return m.matches() ? OptionalInt.of(Integer.parseInt(m.group(2))) : OptionalInt.empty();
    }

    /** "06_Long Beach Street Circuit" → "Long Beach Street Circuit". */
    public static String displayName(String folderName) {
        return ORDINAL_PREFIX.matcher(folderName).replaceFirst("").trim();
    }

    public enum SessionType { PRACTICE, QUALIFYING, RACE, OTHER }

    /**
     * A session folder: its stamp is the session's local wall-clock start (no
     * zone is published), the rest is the label the results name the session
     * by ("Qualifying - GTD Position", "Race 2").
     */
    public record SessionFolder(String folderName, LocalDateTime start, String label, SessionType type) {
    }

    /** Parses "202105151220_Qualifying - GTD Position"; empty for any other folder. */
    public static Optional<SessionFolder> parseSessionFolder(String folderName) {
        Matcher m = SESSION_FOLDER.matcher(folderName);
        if (!m.matches()) {
            return Optional.empty();
        }
        LocalDateTime start;
        try {
            start = LocalDateTime.parse(m.group(1), SESSION_STAMP);
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
        String label = m.group(2).trim();
        return Optional.of(new SessionFolder(folderName, start, label, sessionType(label)));
    }

    /** Type from the label: qualifying, race-like (race / heat / hour), or the
     *  non-scoring sessions (practice, warm-up, test). */
    public static SessionType sessionType(String label) {
        String l = label.toLowerCase(Locale.ROOT);
        if (l.contains("practice") || l.contains("warm") || l.contains("test")) {
            return SessionType.PRACTICE;
        }
        if (l.contains("qualif")) {
            return SessionType.QUALIFYING;
        }
        if (l.contains("race") || l.contains("heat") || l.contains("hour")) {
            return SessionType.RACE;
        }
        return SessionType.OTHER;
    }

    /** "04_Hour 1" / "Hour 24" → the hour; the endurance results sit in the highest. */
    public static OptionalInt hourOf(String folderName) {
        Matcher m = HOUR_FOLDER.matcher(folderName.trim());
        return m.matches() ? OptionalInt.of(Integer.parseInt(m.group(1))) : OptionalInt.empty();
    }

    /** "00_Starting Grids" (2024+). */
    public static boolean isGridsFolder(String folderName) {
        return folderName.toLowerCase(Locale.ROOT).contains("grid");
    }

    /** "00_Points" (2017), "POINTS DATA", "POINTS DATA - Official" (2024+). */
    public static boolean isPointsFolder(String folderName) {
        return folderName.toLowerCase(Locale.ROOT).contains("points");
    }

    // ---------------------------------------------------------------- files

    public enum Kind { RESULTS, GRID, FLAGS, STANDINGS, ENTRY_LIST }

    private static final Pattern RESULTS_NAME = Pattern.compile("(?i)^\\d+_(results|classification)");
    /** Secondary classifications published beside the results, never imported:
     *  in-class positions are derived, the 2nd-fastest sheet only sets a grid
     *  that is imported as its own file. "by Hour" is NOT excluded — in the last
     *  hour folder of an endurance race it is the final classification. */
    private static final List<String> RESULTS_EXCLUDED = List.of(
            "by class", "byclass", "2nd fastest", "by 2nd", "2ndfl", "by division", "by group");

    /**
     * What a file in a session folder is, or empty for the many reports the
     * importer has no use for (lap charts, sector times, weather...).
     */
    public static Optional<Kind> classifySessionFile(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        if (n.contains("flagsanalysis") || n.contains("flags analysis")) {
            return Optional.of(Kind.FLAGS);
        }
        if (n.contains("entry list")) {
            return Optional.of(Kind.ENTRY_LIST);
        }
        if (n.contains("grid") && !n.contains("by number")) {
            // The side-by-side PDF is a print layout of the same grid, skipped —
            // but 2026 publishes its only grid JSON under the "Starting Grid SbS"
            // name, so the JSON is kept whatever it is called.
            boolean sideBySide = n.contains("sbs") || n.contains("side by side");
            if (n.endsWith(".json") || !sideBySide) {
                return Optional.of(Kind.GRID);
            }
            return Optional.empty();
        }
        if (RESULTS_NAME.matcher(n).find() && RESULTS_EXCLUDED.stream().noneMatch(n::contains)) {
            return Optional.of(Kind.RESULTS);
        }
        return Optional.empty();
    }

    /**
     * What a file beside the sessions (series level) is: the championship-points
     * sheet, or the entry list. Award sheets (Trueman-Akin, Front Runner, a
     * "Sprint Cup" PDF) are left alone — no parser reads them.
     */
    public static Optional<Kind> classifySeriesFile(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        if (n.contains("entry list")) {
            return Optional.of(Kind.ENTRY_LIST);
        }
        // "points" alone: 2017 spells it "Champonship Points - Official.pdf".
        if (n.contains("points")) {
            return Optional.of(Kind.STANDINGS);
        }
        return Optional.empty();
    }

    /** Publication status, best first. An unmarked file is the plain
     *  publication of its era (2016–17 named no status at all), ranked below an
     *  explicit Official and above the explicitly interim ones. */
    public enum Status { OFFICIAL, UNMARKED, PROVISIONAL, UNOFFICIAL }

    /** "_Amended 2", " REVISED": a letter boundary, not \b — the file names put
     *  an underscore (a word character) right before the word. */
    private static final Pattern AMENDMENT = Pattern.compile("(?i)(?<!\\p{L})(?:amended|revised)\\s*(\\d+)?");

    /** Status and amendment number read from a file name: "Official_Amended 2"
     *  → (OFFICIAL, 2); "Provisional REVISED" → (PROVISIONAL, 1); plain → 0. */
    public record Revision(Status status, int amendment) {
    }

    public static Revision revisionOf(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        Status status;
        if (n.contains("unofficial")) {
            status = Status.UNOFFICIAL;
        } else if (n.contains("provisional")) {
            status = Status.PROVISIONAL;
        } else if (n.contains("official")) {
            status = Status.OFFICIAL;
        } else {
            status = Status.UNMARKED;
        }
        int amendment = 0;
        Matcher m = AMENDMENT.matcher(fileName);
        if (m.find()) {
            amendment = m.group(1) != null ? Integer.parseInt(m.group(1)) : 1;
        }
        return new Revision(status, amendment);
    }

    /** Upper-case extension without the dot, "" when there is none. */
    public static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toUpperCase(Locale.ROOT);
    }

    /**
     * A file the importer might read, located on the site.
     *
     * @param path     href path relative to the site root (percent-encoded).
     * @param name     decoded file name.
     * @param modified the index's last-modified cell.
     */
    public record SourceFile(String path, String name, String modified, Kind kind,
                             Status status, int amendment, String extension) {

        public static SourceFile of(String path, IndexEntry entry, Kind kind) {
            Revision r = revisionOf(entry.name());
            return new SourceFile(path, entry.name(), entry.modified(), kind,
                    r.status(), r.amendment(), extensionOf(entry.name()));
        }
    }

    /** Machine formats before print ones; unknown extensions last. */
    private static int extensionRank(String ext) {
        return switch (ext) {
            case "JSON" -> 0;
            case "CSV" -> 1;
            case "PDF" -> 2;
            default -> 3;
        };
    }

    /**
     * The order files of one session and kind are preferred in: JSON over CSV
     * over PDF, then Official over unmarked over Provisional over Unofficial,
     * then the highest amendment, then the newest. Format outranks status on
     * purpose — a Provisional JSON reads exactly like the Official one while a
     * PDF needs a parser that may not exist; the refresh flow re-imports once
     * the Official file appears anyway.
     */
    public static Comparator<SourceFile> preference() {
        return Comparator.comparingInt((SourceFile f) -> extensionRank(f.extension()))
                .thenComparing(SourceFile::status)
                .thenComparing(Comparator.comparingInt(SourceFile::amendment).reversed())
                .thenComparing(Comparator.comparing((SourceFile f) -> f.modified() == null ? "" : f.modified())
                        .reversed());
    }

    /** The file to import for one session and kind, restricted to the formats
     *  the importer can read for that kind. */
    public static Optional<SourceFile> best(Collection<SourceFile> files, Kind kind, boolean f1Weekend) {
        return files.stream()
                .filter(f -> formatFor(kind, f.extension(), f1Weekend).isPresent())
                .min(preference());
    }

    /** Extensions the importer reads for a kind — the PDF fallbacks are the
     *  sidecar parsers, and results PDFs only on a Formula 1 support weekend. */
    public static Set<String> importableExtensions(Kind kind, boolean f1Weekend) {
        return switch (kind) {
            case RESULTS -> f1Weekend ? Set.of("JSON", "CSV", "PDF") : Set.of("JSON", "CSV");
            case GRID -> Set.of("JSON", "CSV", "PDF");
            case FLAGS -> Set.of("JSON");
            case STANDINGS -> Set.of("JSON", "PDF");
            case ENTRY_LIST -> Set.of("PDF");
        };
    }

    /**
     * The parser family for a file, or empty when nothing reads it. Carrera Cup
     * NA's Formula 1 support weekends publish F1-paddock sheets (PDF only, the
     * XML beside them is the Unofficial copy), read by {@code F1_PDF} for
     * results, qualifying and grid alike.
     */
    public static Optional<ImportFormat> formatFor(Kind kind, String extension, boolean f1Weekend) {
        if (!importableExtensions(kind, f1Weekend).contains(extension)) {
            return Optional.empty();
        }
        return Optional.of(switch (kind) {
            case RESULTS -> switch (extension) {
                case "JSON" -> ImportFormat.IMSA_JSON;
                case "CSV" -> ImportFormat.IMSA_CSV;
                default -> ImportFormat.F1_PDF;
            };
            case GRID -> switch (extension) {
                case "JSON" -> ImportFormat.IMSA_JSON;
                case "CSV" -> ImportFormat.IMSA_CSV;
                default -> f1Weekend ? ImportFormat.F1_PDF : ImportFormat.IMSA_GRID_PDF;
            };
            case FLAGS -> ImportFormat.IMSA_JSON;
            case STANDINGS -> "JSON".equals(extension) ? ImportFormat.IMSA_JSON : ImportFormat.IMSA_POINTS_PDF;
            case ENTRY_LIST -> ImportFormat.IMSA_PDF;
        });
    }
}
