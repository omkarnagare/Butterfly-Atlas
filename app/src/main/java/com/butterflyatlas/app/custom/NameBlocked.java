package com.butterflyatlas.app.custom;

import com.butterflyatlas.app.photos.TaxonRef;

/**
 * A custom species' name that is already taken (5a contract §2), with the species that has it so the user can open it:
 * {@code in_list} (an official species' name or bundled synonym) or {@code name_exists} (another custom species). The
 * web layer answers 409 with the taxon in the error.
 */
public final class NameBlocked extends RuntimeException {

    public static final String IN_LIST = "in_list";
    public static final String NAME_EXISTS = "name_exists";

    private final String code;
    private final TaxonRef taxon;

    NameBlocked(String code, String message, TaxonRef taxon) {
        super(message);
        this.code = code;
        this.taxon = taxon;
    }

    public String code() {
        return code;
    }

    /** The species that has the name. */
    public TaxonRef taxon() {
        return taxon;
    }
}
