package com.broadcasthelper.imports;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses an iRacing subsession result ("event_result") into normalized import
 * records. One subsession holds every sim-session of a race meeting, so one
 * payload yields several sessions: qualifying, then each race in order.
 *
 * The payload is identical whether it came from /data/results/get or from a
 * file the user exported, so this parser is deliberately source-agnostic — it
 * takes a JsonNode and knows nothing about HTTP. See IRacingClient.
 *
 * Format notes (all seen in the real 2025 Porsche Esports Supercup sample):
 * - times and gaps are integer ten-thousandths of a second; -1 means "none"
 * - positions are 0-based (finish_position 0 is the winner)
 * - a qualifying entrant who set no lap is still classified, with
 *   best_lap_time -1 and reason_out "Running" — not a DNF, just no time
 * - interval is -1 both for a lapped car and for a car with no time; the two
 *   are told apart by lap count, not by the interval itself
 * - practice and warmup sim-sessions are dropped: they score no points and
 *   carry nothing the broadcast uses
 */
public final class IRacingParser {

    // simsession_type values. 3 covers both practice and warmup.
    private static final int TYPE_PRACTICE = 3;
    private static final int TYPE_LONE_QUALIFYING = 4;
    private static final int TYPE_OPEN_QUALIFYING = 5;
    private static final int TYPE_RACE = 6;

    /** iRacing's time unit: ten-thousandths of a second. */
    private static final double TICKS_PER_SECOND = 10_000.0;

    private IRacingParser() {
    }

    public static boolean looksLikeEventResult(JsonNode root) {
        return resultData(root).has("session_results");
    }

    /**
     * The result object, whichever way it arrived. A file exported from iRacing's
     * site wraps it as {"type":"event_result","data":{...}}; the Data API's
     * signed-link payload is that same object unwrapped, with session_results at
     * the top level. Both are supported so the upload and fetch paths share this
     * parser — return the data envelope when present, otherwise the root itself.
     */
    private static JsonNode resultData(JsonNode root) {
        JsonNode data = root.path("data");
        if (data.isObject() && data.has("session_results")) {
            return data;
        }
        return root;
    }

    /**
     * The scoring sessions of one subsession, in running order: qualifying (if
     * any) then each race. Races are numbered by their order of appearance, so a
     * heat-and-feature meeting yields RACE 1 and RACE 2 — the names stay "Heat 1"
     * and "Feature", which is what the broadcast says, while (session_type,
     * ordinal) stays the stable key the domain matches on.
     */
    public static List<RaceResultsImport> parseSessions(JsonNode root) {
        JsonNode data = resultData(root);
        List<RaceResultsImport> out = new ArrayList<>();
        int raceOrdinal = 0;
        for (JsonNode sim : data.path("session_results")) {
            int type = sim.path("simsession_type").asInt();
            if (type == TYPE_PRACTICE) {
                continue;
            }
            String sessionType;
            int ordinal;
            if (type == TYPE_LONE_QUALIFYING || type == TYPE_OPEN_QUALIFYING) {
                sessionType = "Qualifying";
                ordinal = 1;
            } else if (type == TYPE_RACE) {
                sessionType = "Race";
                ordinal = ++raceOrdinal;
            } else {
                continue;
            }
            out.add(new RaceResultsImport(
                    text(data, "league_name"),
                    eventName(data),
                    sessionName(sim),
                    sessionType,
                    ordinal,
                    null, // iRacing has no report mark; a hosted session is never "Official"
                    null,
                    sessionStart(data),
                    circuitName(data),
                    null, // the result payload carries no track length or country;
                    null, // /data/track/get has both, but that is the API path's job
                    resultRows(data, sim)
            ));
        }
        return out;
    }

    /**
     * The starting grid of each race, taken verbatim from starting_position.
     * It is never derived: in a heat format the feature grid is a partial
     * reversal of the heat result, so neither the qualifying order nor the heat
     * order reproduces it.
     */
    public static List<GridImport> parseGrids(JsonNode root) {
        JsonNode data = resultData(root);
        List<GridImport> out = new ArrayList<>();
        int raceOrdinal = 0;
        for (JsonNode sim : data.path("session_results")) {
            if (sim.path("simsession_type").asInt() != TYPE_RACE) {
                continue;
            }
            int ordinal = ++raceOrdinal;
            List<JsonNode> starters = new ArrayList<>();
            for (JsonNode r : sim.path("results")) {
                if (r.path("starting_position").asInt(-1) >= 0) {
                    starters.add(r);
                }
            }
            starters.sort((a, b) -> Integer.compare(
                    a.path("starting_position").asInt(), b.path("starting_position").asInt()));

            List<GridImport.Row> rows = new ArrayList<>();
            for (JsonNode r : starters) {
                rows.add(new GridImport.Row(
                        r.path("starting_position").asInt() + 1,
                        r.path("starting_position_in_class").asInt() + 1,
                        carNumber(r),
                        text(r, "car_class_short_name"),
                        null,
                        text(r, "display_name"), // solo league: the driver is the entry
                        text(r, "car_name"),
                        null,
                        null, // the grid slot carries no time; qualifying holds it
                        // iRacing names no per-seat attribution; a solo entry's
                        // qualifier/starter is resolved at read time (sole crew member).
                        null,
                        null,
                        List.of()
                ));
            }
            out.add(new GridImport(
                    text(data, "league_name"),
                    eventName(data),
                    sessionName(sim),
                    "Race",
                    ordinal,
                    sessionStart(data),
                    circuitName(data),
                    null,
                    null,
                    rows
            ));
        }
        return out;
    }

    // ------------------------------------------------------ league navigation

    /**
     * One scheduled round of a league season: the subsession to import, plus
     * enough to recognise it (date, track, winner) without opening it.
     */
    public record LeagueRound(
            long subsessionId,
            OffsetDateTime launchAt,
            String trackName,
            String winnerName,
            boolean hasResults,
            int entryCount
    ) {
    }

    /**
     * The rounds of a /league/season_sessions payload, oldest first. A scheduled
     * round with no results yet (hasResults false) is still listed — it carries a
     * subsession id but importing it would find nothing, so the caller decides.
     */
    public static List<LeagueRound> parseSeasonRounds(JsonNode root) {
        List<LeagueRound> rounds = new ArrayList<>();
        for (JsonNode s : root.path("sessions")) {
            long subsessionId = s.path("subsession_id").asLong(-1);
            if (subsessionId < 0) {
                continue; // a slot with no subsession is nothing to import
            }
            String launch = text(s, "launch_at");
            rounds.add(new LeagueRound(
                    subsessionId,
                    launch == null ? null : OffsetDateTime.parse(launch),
                    text(s.path("track"), "track_name"),
                    text(s, "winner_name"),
                    s.path("has_results").asBoolean(false),
                    s.path("entry_count").asInt(0)
            ));
        }
        rounds.sort((a, b) -> {
            if (a.launchAt() == null || b.launchAt() == null) {
                return 0;
            }
            return a.launchAt().compareTo(b.launchAt());
        });
        return rounds;
    }

    /**
     * A league season's driver championship from the /league/season_standings
     * payload alone. The endpoint gives season totals only — base points plus
     * manual adjustments, no per-round split — so each row's per-session
     * breakdown is empty and no rounds are named. {@link #assembleSeasonStandings}
     * fills those in from the individual rounds' results; this thin form is the
     * fallback for when no round results are available.
     */
    public static StandingsImport parseSeasonStandings(JsonNode root, String seasonName, String year) {
        return assembleSeasonStandings(root, seasonName, year, List.of(), java.util.Map.of());
    }

    /**
     * A league season's driver championship with its per-round points filled in.
     * The standings endpoint names the competitors and their season totals; the
     * per-round breakdown the recap needs is pulled from each round's own result
     * (see {@link #parseRoundLeaguePoints}) and passed in here.
     *
     * The competitor is the driver (a solo league), keyed by cust_id so a rename
     * doesn't fork the row. total_points stays the endpoint's authoritative value
     * — it already folds in any manual adjustments (post-race penalties), which
     * the per-round race points can't know about — so the row total and the
     * per-round column sum can legitimately differ by those adjustments.
     *
     * @param sessions             the season's scoring rounds, oldest first
     * @param pointsByCustSession  cust_id → (session_index → league points that round);
     *                             a driver absent from a round's map raced no points there
     */
    public static StandingsImport assembleSeasonStandings(
            JsonNode root, String seasonName, String year,
            List<StandingsImport.SessionRef> sessions,
            java.util.Map<Long, java.util.Map<Integer, Double>> pointsByCustSession) {
        List<StandingsImport.Row> rows = new ArrayList<>();
        for (JsonNode d : root.path("standings").path("driver_standings")) {
            JsonNode driver = d.path("driver");
            long custId = driver.path("cust_id").asLong();
            java.util.Map<Integer, Double> perRound =
                    pointsByCustSession.getOrDefault(custId, java.util.Map.of());
            List<StandingsImport.SessionPoints> points = new ArrayList<>();
            for (StandingsImport.SessionRef s : sessions) {
                Double pts = perRound.get(s.sessionIndex());
                if (pts == null) {
                    continue; // the driver didn't take part in this round — leave it blank
                }
                // iRacing scores a single league-points figure per round; there is
                // no pole/fastest-lap/penalty split to record (post-race penalties
                // arrive as the standings' manual adjustments, not per session).
                points.add(new StandingsImport.SessionPoints(
                        s.sessionIndex(), pts, pts, 0, 0, 0, 0, "race"));
            }
            rows.add(new StandingsImport.Row(
                    d.path("position").asInt(),
                    String.valueOf(custId),
                    text(driver, "display_name"),
                    d.path("total_points").asDouble(),
                    null,
                    null,
                    points
            ));
        }
        rows.sort((a, b) -> Integer.compare(a.position(), b.position()));
        return new StandingsImport(seasonName, seasonName, null, year, sessions, rows);
    }

    /**
     * The venue name a round should carry in the championship calendar — the same
     * string {@link #parseSessions} names the round's event with, so the recap's
     * column label reads the same as the event it sits above. The recap matches
     * rounds to events by ordinal, not by this name, so it is a label rather than
     * a key; keeping it identical just avoids a column labelled unlike its event.
     */
    public static String roundVenueName(JsonNode result) {
        return circuitName(resultData(result));
    }

    /**
     * One round's league championship points per driver, keyed by cust_id. iRacing
     * has already scored these — league_points on each race result row — so no
     * points formula is reinvented here; a round with more than one race sim-session
     * (a sprint-plus-feature round) sums them. Qualifying and practice score
     * nothing and are skipped. Team-shell rows (no cust_id) are ignored — this is
     * the drivers championship.
     */
    public static java.util.Map<Long, Double> parseRoundLeaguePoints(JsonNode result) {
        JsonNode data = resultData(result);
        java.util.Map<Long, Double> byCust = new java.util.HashMap<>();
        for (JsonNode sim : data.path("session_results")) {
            if (sim.path("simsession_type").asInt() != TYPE_RACE) {
                continue;
            }
            for (JsonNode r : sim.path("results")) {
                long custId = r.path("cust_id").asLong(-1);
                if (custId <= 0) {
                    continue;
                }
                byCust.merge(custId, r.path("league_points").asDouble(0), Double::sum);
            }
        }
        return byCust;
    }

    private static List<RaceResultsImport.Row> resultRows(JsonNode data, JsonNode sim) {
        List<JsonNode> finishers = new ArrayList<>();
        for (JsonNode r : sim.path("results")) {
            finishers.add(r);
        }
        finishers.sort((a, b) -> Integer.compare(
                a.path("finish_position").asInt(), b.path("finish_position").asInt()));

        int leaderLaps = finishers.isEmpty() ? 0 : finishers.get(0).path("laps_complete").asInt();

        List<RaceResultsImport.Row> rows = new ArrayList<>();
        for (int i = 0; i < finishers.size(); i++) {
            JsonNode r = finishers.get(i);
            JsonNode prev = i > 0 ? finishers.get(i - 1) : null;

            long bestLap = r.path("best_lap_time").asLong(-1);
            String reasonOut = text(r, "reason_out");
            boolean running = reasonOut == null || "Running".equalsIgnoreCase(reasonOut);
            // A qualifying entrant with no lap is classified, not retired: they
            // took no part rather than failing. Saying "No Time" keeps them off
            // the DNF list they don't belong on.
            boolean noTime = running && bestLap < 0;
            String status = !running ? reasonOut : noTime ? "No Time" : "Classified";

            rows.add(new RaceResultsImport.Row(
                    r.path("finish_position").asInt() + 1,
                    r.path("finish_position_in_class").asInt() + 1,
                    carNumber(r),
                    text(r, "car_class_short_name"),
                    null,
                    text(r, "display_name"),
                    text(r, "car_name"),
                    null,
                    status,
                    !running,
                    running ? null : reasonOut,
                    r.path("laps_complete").asInt(),
                    null, // no elapsed race time in the payload, only intervals
                    gapFirst(r, i, leaderLaps),
                    gapPrevious(r, prev, leaderLaps),
                    lapTime(bestLap),
                    positiveOrNull(r.path("best_lap_num").asInt(-1)),
                    null, // no trap speed; kph needs a track length the payload lacks
                    1,    // which seat set the fastest lap isn't in the payload
                    null, // pit stops are in /data/results/event_log, not here
                    driverRows(data, r)
            ));
        }
        return rows;
    }

    /**
     * The crew of one result entry. A team entry names the team in its own
     * display_name and carries the real drivers — each with its own cust_id — in
     * driver_results; a solo entry has no driver_results and is its own driver
     * (it carries a cust_id directly). A team shell with no recorded crew yields
     * no drivers rather than mistaking the team name for one.
     */
    private static List<RaceResultsImport.DriverRow> driverRows(JsonNode data, JsonNode entry) {
        JsonNode crew = entry.path("driver_results");
        if (crew.isArray() && crew.size() > 0) {
            List<RaceResultsImport.DriverRow> out = new ArrayList<>();
            int seat = 1;
            for (JsonNode d : crew) {
                out.add(driverRow(data, d, seat++));
            }
            return out;
        }
        if (entry.has("cust_id")) {
            return List.of(driverRow(data, entry, 1));
        }
        return List.of();
    }

    private static RaceResultsImport.DriverRow driverRow(JsonNode data, JsonNode driver, int seat) {
        String name = text(driver, "display_name");
        String first = "";
        String surname = name == null ? "" : name;
        if (name != null) {
            // Surname is the last whitespace-separated token, so middle names and
            // initials stay with the forename ("Florian A Lebigre" -> "Florian A"
            // / "Lebigre"). Real entries in the sample carry both.
            String[] parts = name.trim().split("\\s+");
            if (parts.length > 1) {
                surname = parts[parts.length - 1];
                first = String.join(" ", java.util.Arrays.copyOf(parts, parts.length - 1));
            }
        }
        return new RaceResultsImport.DriverRow(
                seat,
                first,
                surname,
                licence(data, driver),
                null, // no hometown in the result payload
                text(driver, "country_code")
        );
    }

    /**
     * The driver's licence class in this subsession's category ("A"), which lines
     * up with the single letter the domain stores for every series — IMSA's
     * "Platinum" lands there as "P" (see ImportService.ratingLetter).
     *
     * The safety rating that iRacing shows beside the class ("A 4.99") is
     * deliberately dropped rather than appended: the letter is all that survives
     * persistence, so returning the pair here would only look like it was kept.
     *
     * driver_licenses is keyed by cust_id and holds one entry per category; only
     * the category being raced counts. Absent unless the payload was fetched with
     * include_licenses.
     */
    private static String licence(JsonNode data, JsonNode r) {
        JsonNode licences = data.path("driver_licenses").path(r.path("cust_id").asText());
        if (!licences.isArray()) {
            return null;
        }
        int category = data.path("license_category_id").asInt(-1);
        for (JsonNode l : licences) {
            if (l.path("category_id").asInt() != category) {
                continue;
            }
            String group = l.path("group_name").asText("");
            // "Class A" -> "A"; "Pro/WC" and "Rookie" already lead with their letter.
            String letter = group.startsWith("Class ") ? group.substring("Class ".length()) : group;
            return letter.isBlank() ? null : letter;
        }
        return null;
    }

    /** Gap to the leader: "-", "+11.166", or "1 Lap" for a car not on the lead lap. */
    private static String gapFirst(JsonNode r, int index, int leaderLaps) {
        if (index == 0) {
            return "-";
        }
        long interval = r.path("interval").asLong(-1);
        if (interval >= 0) {
            return "+" + seconds(interval);
        }
        return lapsText(leaderLaps - r.path("laps_complete").asInt());
    }

    /**
     * Gap to the car ahead, derived by differencing intervals — iRacing publishes
     * only the gap to the leader. Null when neither a time nor a lap separates
     * them, which is how two no-time qualifiers end up adjacent.
     */
    private static String gapPrevious(JsonNode r, JsonNode prev, int leaderLaps) {
        if (prev == null) {
            return "-";
        }
        long interval = r.path("interval").asLong(-1);
        long prevInterval = prev.path("interval").asLong(-1);
        if (interval >= 0 && prevInterval >= 0) {
            return "+" + seconds(interval - prevInterval);
        }
        int lapsDown = leaderLaps - r.path("laps_complete").asInt();
        int prevLapsDown = leaderLaps - prev.path("laps_complete").asInt();
        return lapsText(lapsDown - prevLapsDown);
    }

    private static String lapsText(int laps) {
        if (laps <= 0) {
            return null;
        }
        return laps + (laps == 1 ? " Lap" : " Laps");
    }

    /** A lap time as "1:44.601"; null when there is none (-1). */
    static String lapTime(long ticks) {
        if (ticks < 0) {
            return null;
        }
        long totalMillis = Math.round(ticks / TICKS_PER_SECOND * 1000.0);
        long minutes = totalMillis / 60_000;
        long millis = totalMillis % 60_000;
        long hours = minutes / 60;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%d:%02d:%06.3f",
                    hours, minutes % 60, millis / 1000.0);
        }
        return String.format(Locale.ROOT, "%d:%06.3f", minutes, millis / 1000.0);
    }

    /** A gap as plain seconds to three places ("11.166"), matching the sheet. */
    static String seconds(long ticks) {
        return String.format(Locale.ROOT, "%.3f", ticks / TICKS_PER_SECOND);
    }

    /**
     * Car numbers live on the livery, not the result row, and are strings —
     * leading zeros are real and must never be parsed as integers.
     */
    private static String carNumber(JsonNode r) {
        return text(r.path("livery"), "car_number");
    }

    /**
     * iRacing names no event, so the track stands in for one. Two rounds at the
     * same track in a season would collide on event name; the reviewer renames
     * or picks the existing event at commit.
     */
    private static String eventName(JsonNode data) {
        return circuitName(data);
    }

    /**
     * "Daytona International Speedway Road Course" — the config is part of the
     * circuit. A track with no separate layout reports config_name "N/A" (seen at
     * Sachsenring), which must be dropped rather than tacked on as "Sachsenring
     * N/A". Package-private for a targeted test of that.
     */
    static String circuitName(JsonNode data) {
        JsonNode track = data.path("track");
        String name = text(track, "track_name");
        String config = text(track, "config_name");
        if (name == null) {
            return null;
        }
        if (config == null || config.equalsIgnoreCase("N/A")) {
            return name;
        }
        return name + " " + config;
    }

    /**
     * Every sim-session shares the subsession's start time: the payload times
     * individual laps but never says when qualifying itself went green.
     *
     * iRacing stamps a real UTC instant, but sessionStart is a LocalDateTime —
     * for a timing-provider file it means wall-clock time at the track, which an
     * online race has none of. It reaches a timestamptz column through the JVM's
     * default zone, so the instant only survives if it is shifted into that zone
     * first; handing over the UTC reading would move the race by the JVM's offset
     * (five hours, on a US-east machine).
     */
    private static LocalDateTime sessionStart(JsonNode data) {
        String raw = text(data, "start_time");
        return raw == null ? null
                : OffsetDateTime.parse(raw).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }

    /** "HEAT 1" -> "Heat 1", "FEATURE" -> "Feature", "QUALIFY" -> "Qualifying". */
    private static String sessionName(JsonNode sim) {
        String raw = text(sim, "simsession_name");
        if (raw == null) {
            return null;
        }
        if ("QUALIFY".equalsIgnoreCase(raw)) {
            return "Qualifying";
        }
        StringBuilder out = new StringBuilder();
        for (String word : raw.trim().split("\\s+")) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0)))
                    .append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }

    private static Integer positiveOrNull(int value) {
        return value > 0 ? value : null;
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText("");
        return value.isBlank() ? null : value.trim();
    }
}
