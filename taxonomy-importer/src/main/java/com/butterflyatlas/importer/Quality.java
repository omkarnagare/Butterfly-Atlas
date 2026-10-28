package com.butterflyatlas.importer;

/** Which iNaturalist observations count as evidence that a species occurs in the region. */
public enum Quality {
    /** Community-confirmed identifications only. */
    RESEARCH("research", "research-grade", "research-grade observations only"),
    /** Any observation with a photo, date and place, confirmed or not. */
    VERIFIABLE("verifiable", "verifiable", "all verifiable observations");

    private final String label;
    private final String noteWording;
    private final String description;

    Quality(String label, String noteWording, String description) {
        this.label = label;
        this.noteWording = noteWording;
        this.description = description;
    }

    /** Adjective used in the bundle's source note, e.g. "verifiable" or "research-grade". */
    public String noteWording() {
        return noteWording;
    }

    /** Human-readable summary for the CLI, e.g. "all verifiable observations". */
    public String description() {
        return description;
    }

    public String label() {
        return label;
    }

    /** Extra query parameter for the species-counts call ({@code verifiable=true} is always sent). */
    public String queryParam() {
        return this == RESEARCH ? "&quality_grade=research" : "";
    }

    public static Quality parse(String value) {
        for (Quality quality : values()) {
            if (quality.label.equalsIgnoreCase(value)) {
                return quality;
            }
        }
        throw new IllegalArgumentException("quality must be 'research' or 'verifiable', got: " + value);
    }
}
