package com.pitpass.imports;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Session-name rules for events that run several sessions of one type that a
 * trailing number can't tell apart.
 *
 * A session is normally keyed (event, type, ordinal) with the ordinal read from
 * the name's trailing number ("Race 2"), so "Race" and "Race 1" are the same
 * session under name drift. Some weekends split a type instead: 2021 IMSA
 * WeatherTech qualified each class group separately ("Qualifying - GTD
 * Position", "Qualifying - GTD Points GTLM", "Qualifying - LMP3 Position -
 * Points"), the Roar split qualifying by class, and a postponed race can be run
 * at a later event ("Miami Make-Up - Race 2"). Those names carry a <em>split
 * label</em> — text beyond the type word and number — and each label is its
 * own session.
 *
 * 2021's split qualifying also separated grid from points: "GTD Position" set
 * the GTD grid while "GTD Points" only scored championship points. A class
 * marked Points (and not Position) in a session's name is points-only there,
 * and its results don't count as qualifying for poles or the sheet.
 */
final class SessionNames {

    private static final Pattern QUALIFYING_WORD = Pattern.compile("(?i)\\bqualif\\w*");
    private static final Pattern RACE_WORD = Pattern.compile("(?i)\\brace\\b");
    private static final Pattern PRACTICE_WORD = Pattern.compile("(?i)\\bpractice\\b");
    /** A trailing ordinal is a separate word ("Race 2"), never a class's digit ("LMP2"). */
    private static final Pattern TRAILING_NUMBER = Pattern.compile("(?:^|\\s)\\d+\\s*$");
    private static final String EDGE = "[\\s\\-–—_:|]+";

    /** Al Kamel file names: "03_Results_Qualifying - GTD Position.CSV",
     *  "00_Grid_Race 2_Official_Amended 2.CSV", "03_Results_Race_Official.CSV". */
    private static final Pattern ALKAMEL_FILE = Pattern.compile(
            "^\\d+_(?:Results(?: by [^_]+)?|Grid|Starting Grid(?: [^_]+)?|Classification\\w*)_(?<session>[^_]+?)"
            + "(?:_(?:Official|Provisional|Unofficial)(?:[_ ].*)?)?\\.(?:csv|json|pdf)$",
            Pattern.CASE_INSENSITIVE);

    private SessionNames() {
    }

    /**
     * The part of a session name beyond its type word and trailing number, or
     * null when there is none. Only a name that names its type counts: "Heat 1"
     * or "Feature" are race sessions keyed by ordinal, not split sessions.
     *
     * "Qualifying - GTD Position" -> "GTD Position"; "Race 2" -> null;
     * "Miami Make-Up - Race 2" -> "Miami Make-Up".
     */
    static String splitLabel(String sessionType, String name) {
        if (name == null || sessionType == null) {
            return null;
        }
        Pattern typeWord = switch (sessionType) {
            case "QUALIFYING" -> QUALIFYING_WORD;
            case "RACE" -> RACE_WORD;
            case "PRACTICE" -> PRACTICE_WORD;
            default -> null;
        };
        if (typeWord == null) {
            return null;
        }
        Matcher m = typeWord.matcher(name);
        if (!m.find()) {
            return null;
        }
        String rest = (name.substring(0, m.start()) + " " + name.substring(m.end())).trim();
        rest = TRAILING_NUMBER.matcher(rest).replaceFirst("");
        rest = rest.replaceAll("^" + EDGE, "").replaceAll(EDGE + "$", "").replaceAll("\\s+", " ");
        return rest.isEmpty() ? null : rest;
    }

    /** The session name an Al Kamel file name carries, or null for any other name. */
    static String sessionNameFromFilename(String filename) {
        if (filename == null) {
            return null;
        }
        String base = filename.replaceAll("^.*[/\\\\]", "")
                .replaceAll("\\s*\\(\\d+\\)(?=\\.[^.]+$)", ""); // "file (1).csv" browser duplicates
        Matcher m = ALKAMEL_FILE.matcher(base);
        return m.matches() ? m.group("session").trim() : null;
    }

    /** Comparable form of a session name: case, spacing and separators ignored. */
    static String normalize(String name) {
        return name == null ? null
                : name.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    /**
     * Classes this session scored points for without setting their grid: those
     * named with "Points" but not "Position". Each class's qualifier is the text
     * between its name and the next class named in the label.
     *
     * "GTD Points GTLM" with {GTD, GTLM} -> {GTD}; "GTD Position" -> {};
     * "LMP3 Position - Points" -> {}.
     */
    static Set<String> pointsOnlyClasses(String label, Iterable<String> classes) {
        Set<String> out = new LinkedHashSet<>();
        if (label == null) {
            return out;
        }
        String lower = label.toLowerCase(Locale.ROOT);
        record Hit(String cls, int start, int end) {
        }
        java.util.List<Hit> hits = new java.util.ArrayList<>();
        for (String cls : classes) {
            if (cls == null || cls.isBlank()) {
                continue;
            }
            Matcher m = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(cls.toLowerCase(Locale.ROOT))
                    + "(?![\\p{L}\\p{N}])").matcher(lower);
            if (m.find()) {
                hits.add(new Hit(cls, m.start(), m.end()));
            }
        }
        hits.sort(java.util.Comparator.comparingInt(Hit::start));
        for (int i = 0; i < hits.size(); i++) {
            Hit h = hits.get(i);
            int stop = i + 1 < hits.size() ? hits.get(i + 1).start() : lower.length();
            String qualifier = lower.substring(h.end(), Math.max(h.end(), stop));
            if (qualifier.contains("point") && !qualifier.contains("position")) {
                out.add(h.cls());
            }
        }
        return out;
    }
}
