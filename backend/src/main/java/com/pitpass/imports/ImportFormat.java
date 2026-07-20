package com.pitpass.imports;

/**
 * A parser family: source provider x medium. Each format owns one parser
 * module; document-kind detection (results vs grid vs ...) lives inside the
 * family. AUTO is an upload-time option resolved to a concrete format before
 * staging — import_batch.format always records the resolved family.
 */
public enum ImportFormat {
    AUTO(null, null, "Auto-detect"),
    IMSA_JSON("IMSA", Medium.JSON, "IMSA — JSON (results/standings/grid/entry list)"),
    IMSA_PDF("IMSA", Medium.PDF, "IMSA — Entry list PDF"),
    // Both PDF families need Python to tell them apart, so AUTO can't: it treats
    // any PDF as an entry list and this one must be chosen explicitly, the same
    // way a grid CSV is. Prefer the JSON where the series publishes one — it
    // splits pole from fastest lap, which the PDF cannot.
    IMSA_POINTS_PDF("IMSA", Medium.PDF, "IMSA — Championship points PDF"),
    IMSA_CSV("IMSA", Medium.CSV, "IMSA — Grid CSV"),
    // One subsession export holds a whole meeting — qualifying, every race, and
    // each race's grid — so it stages as several batches. The same payload comes
    // back from the Data API, so IRacingClient feeds this format's parser too.
    IRACING_JSON("iRacing", Medium.JSON, "iRacing — Subsession result JSON");

    public enum Medium { JSON, PDF, CSV }

    private final String provider;
    private final Medium medium;
    private final String label;

    ImportFormat(String provider, Medium medium, String label) {
        this.provider = provider;
        this.medium = medium;
        this.label = label;
    }

    public String provider() {
        return provider;
    }

    public Medium medium() {
        return medium;
    }

    public String label() {
        return label;
    }
}
