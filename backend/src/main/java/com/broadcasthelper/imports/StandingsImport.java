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
            List<SessionPoints> pointsBySession
    ) {
    }

    public record SessionPoints(
            int sessionIndex,
            double totalPoints,
            double racePoints,
            double polePoints,
            double fastestLapPoints,
            double penaltyPoints,
            String status
    ) {
    }
}
