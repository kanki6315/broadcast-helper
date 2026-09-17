package com.pitpass.imports.alkamel;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * One row of an Apache directory index on the Al Kamel results site.
 *
 * @param href      the link as served — percent-encoded, with a trailing slash
 *                  for a folder. Paths are built by concatenating hrefs, so a
 *                  URL is never re-encoded from a decoded name (spaces, "&"
 *                  and "+" in folder names would otherwise round-trip wrong).
 * @param name      the decoded name without its trailing slash, for display
 *                  and for the catalog's rules.
 * @param directory whether the row is a folder.
 * @param modified  the index's "Last modified" cell, "yyyy-MM-dd HH:mm" as
 *                  served (the site's own clock; only compared to itself).
 */
public record IndexEntry(String href, String name, boolean directory, String modified) {

    private static final DateTimeFormatter MODIFIED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    static IndexEntry of(String href, String modified) {
        boolean dir = href.endsWith("/");
        String decoded = URLDecoder.decode(href.replace("+", "%2B"), StandardCharsets.UTF_8);
        String name = dir ? decoded.substring(0, decoded.length() - 1) : decoded;
        return new IndexEntry(href, name, dir, modified);
    }

    /** The modified cell as a timestamp, or null when the cell isn't one. */
    public LocalDateTime modifiedAt() {
        if (modified == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(modified.trim(), MODIFIED);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
