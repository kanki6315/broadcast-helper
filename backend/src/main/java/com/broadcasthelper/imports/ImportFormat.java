package com.broadcasthelper.imports;

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
    IMSA_CSV("IMSA", Medium.CSV, "IMSA — Grid CSV");

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
