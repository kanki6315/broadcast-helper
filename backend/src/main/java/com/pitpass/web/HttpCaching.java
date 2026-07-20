package com.pitpass.web;

/** Shared cache-control policy for binary assets (car images, logos, team-sheet PDFs). */
public final class HttpCaching {

    private HttpCaching() {
    }

    /**
     * Assets are rendered at version-stamped URLs (?v=uploaded_at millis), so a
     * given URL never changes content: mark it immutable with a one-year TTL and
     * the browser serves it from disk cache with no revalidation on reload —
     * replacing an asset bumps the version, which is a new URL. A hit that
     * carries no version stays short-lived so a direct/un-stamped fetch can't go
     * stale. public: the bytes are identical for every viewer.
     */
    public static String cacheControl(boolean versioned) {
        return versioned ? "public, max-age=31536000, immutable" : "public, max-age=300";
    }
}
