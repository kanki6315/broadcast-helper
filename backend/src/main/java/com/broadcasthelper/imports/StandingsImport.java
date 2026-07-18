package com.broadcasthelper.imports;

import java.util.List;

/**
 * Normalized form of a championship standings JSON: the season calendar of
 * points-scoring sessions plus each competitor's points broken down per session.
 */
public record StandingsImport(
        String name,
        String mainTitle,
        String subTitle,
        String year,
        List<SessionRef> sessions,
        List<Row> rows
) {

    public record SessionRef(int sessionIndex, String eventName, String sessionName) {
    }

    public record Row(
            int position,
            String key,
            String team,
            double totalPoints,
            Integer netPosition,
            Double totalNetPoints,
            Adjustments adjustments,
            List<SessionPoints> pointsBySession
    ) {
    }

    /**
     * Manual corrections a league applied to a competitor's season total, kept
     * apart from the per-session points because that is where they happen: a
     * steward rules after the fact, on the season, with no round to attribute
     * it to. {@code totalPoints} is the figure after them; {@code basePoints} is
     * what the per-session columns add up to.
     *
     * Null on any source that reports no such thing — an IMSA standings JSON,
     * or an official iRacing series, whose totals are the raw sum.
     */
    public record Adjustments(double basePoints, double positive, double negative) {
    }

    /**
     * A session's points for one competitor. pole/fastestLap/penalty come from a
     * standings JSON; bonusPoints is the points-PDF's single undifferentiated
     * "Extra" column, which cannot be split back into pole vs fastest lap (see
     * V21__standings_bonus_points.sql). A source populates one or the other,
     * never both.
     */
    public record SessionPoints(
            int sessionIndex,
            double totalPoints,
            double racePoints,
            double polePoints,
            double fastestLapPoints,
            double penaltyPoints,
            double bonusPoints,
            String status
    ) {
    }
}
