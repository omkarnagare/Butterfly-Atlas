package com.butterflyatlas.placeimporter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The official Local Government Directory district list (names and LGD codes, no boundaries), read from the CSV the
 * developer passes with {@code --lgd}, and matched to the boundary districts: by state and name key, through the
 * reviewed override table for renamed districts, then by the name inside or before parentheses when that is unique;
 * each district is tried in the states it may belong to, most likely first (every kind of match in a state before
 * the next state).
 */
public final class Lgd {

    public record District(String stateCode, String stateName, String code, String name) {
    }

    /**
     * A boundary unit: a state ({@code id} = its ISO code, no {@code states}) or a district with the ISO codes of
     * the states it may belong to, most likely first (the sources' state and district borders do not always agree:
     * geoBoundaries puts most of Yanam, a district of Puducherry, in Andhra Pradesh).
     */
    public record Boundary(String id, List<String> states, String name) {
    }

    /** A reviewed match the names cannot make: this boundary district is that LGD district. */
    public record Override(String stateIso, String boundaryName, String lgdCode) {
    }

    /**
     * {@code districts} and {@code districtStates} are keyed by boundary id; the state is the matched district's.
     * {@code overridesWithoutLgd}: reviewed overrides whose code this (older or partial) list lacks; they are not used.
     */
    public record Match(Map<String, String> stateCodes, List<String> statesWithoutLgd, Map<String, District> districts,
                        Map<String, String> districtStates, List<Boundary> boundariesWithoutLgd,
                        List<District> lgdWithoutBoundary, List<Override> overridesWithoutLgd) {
    }

    private Lgd() {
    }

    public static List<District> read(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            List<List<String>> rows = csv(reader);
            List<String> header = rows.isEmpty() ? List.of() : rows.get(0);
            int stateCode = column(file, header, "State Code");
            int stateName = column(file, header, "State Name (In English)");
            int code = column(file, header, "District Code");
            int name = column(file, header, "District Name(In English)");
            int last = Math.max(Math.max(stateCode, stateName), Math.max(code, name));
            List<District> districts = new ArrayList<>();
            for (List<String> row : rows.subList(1, rows.size())) {
                if (row.size() > last && !row.get(code).isBlank()) {
                    districts.add(new District(row.get(stateCode).trim(), row.get(stateName).trim(), row.get(code).trim(),
                            row.get(name).trim()));
                }
            }
            return districts;
        }
    }

    public static List<Override> readOverrides(InputStream in) throws IOException {
        List<List<String>> rows = csv(new InputStreamReader(in, StandardCharsets.UTF_8));
        List<Override> overrides = new ArrayList<>();
        for (List<String> row : rows.subList(1, rows.size())) {
            if (row.size() >= 3 && !row.get(0).isBlank()) {
                overrides.add(new Override(row.get(0).trim(), row.get(1).trim(), row.get(2).trim()));
            }
        }
        return overrides;
    }

    public static Match match(List<Boundary> states, List<Boundary> districts, List<District> lgd, List<Override> overrides) {
        Map<String, String> lgdStateByKey = new HashMap<>();
        for (District d : lgd) {
            lgdStateByKey.put(Names.key(d.stateName()), d.stateCode());
        }
        Map<String, String> stateCodes = new LinkedHashMap<>();
        List<String> statesWithoutLgd = new ArrayList<>();
        for (Boundary state : states) {
            String code = lgdStateByKey.get(Names.key(state.name()));
            if (code == null) {
                statesWithoutLgd.add(state.id());
            } else {
                stateCodes.put(state.id(), code);
            }
        }
        Map<String, District> byCode = new HashMap<>();
        lgd.forEach(d -> byCode.put(d.code(), d));
        Map<String, String> overrideCodes = new HashMap<>();
        List<Override> overridesWithoutLgd = new ArrayList<>();
        for (Override o : overrides) {
            District d = byCode.get(o.lgdCode());
            if (d == null) {
                overridesWithoutLgd.add(o);   // not in this (older or partial) list: the names decide
                continue;
            }
            if (!d.stateCode().equals(stateCodes.get(o.stateIso()))) {
                throw new IllegalArgumentException("LGD override " + o + ": LGD district " + o.lgdCode() + " (" + d.name()
                        + ") is not in " + o.stateIso());
            }
            overrideCodes.put(o.stateIso() + "/" + Names.key(o.boundaryName()), o.lgdCode());
        }

        Map<String, District> matched = new LinkedHashMap<>();
        Map<String, String> matchedStates = new LinkedHashMap<>();
        List<Boundary> unmatched = new ArrayList<>();
        for (Boundary b : districts) {
            District found = null;
            String foundState = null;
            // The most likely state first, with every kind of match, before the next state.
            for (String stateIso : b.states()) {
                String stateCode = stateCodes.get(stateIso);
                if (stateCode == null) {
                    continue;
                }
                String code = overrideCodes.get(stateIso + "/" + Names.key(b.name()));
                found = code != null ? byCode.get(code) : unique(lgd, stateCode, List.of(Names.key(b.name())), false);
                if (found == null) {
                    found = unique(lgd, stateCode, Names.keys(b.name()), true);
                }
                if (found != null) {
                    foundState = stateIso;
                    break;
                }
            }
            if (found == null) {
                unmatched.add(b);
            } else {
                matched.put(b.id(), found);
                matchedStates.put(b.id(), foundState);
            }
        }
        Set<String> used = new HashSet<>();
        matched.values().forEach(d -> used.add(d.code()));
        List<District> withoutBoundary = lgd.stream().filter(d -> !used.contains(d.code())).toList();
        return new Match(stateCodes, statesWithoutLgd, matched, matchedStates, unmatched, withoutBoundary,
                List.copyOf(overridesWithoutLgd));
    }

    /** The one LGD district of the state whose (full, or any when {@code variants}) key is among {@code keys}. */
    private static District unique(List<District> lgd, String stateCode, List<String> keys, boolean variants) {
        District found = null;
        for (District d : lgd) {
            if (!d.stateCode().equals(stateCode)) {
                continue;
            }
            List<String> theirs = variants ? Names.keys(d.name()) : List.of(Names.key(d.name()));
            if (theirs.stream().anyMatch(keys::contains)) {
                if (found != null) {
                    return null;
                }
                found = d;
            }
        }
        return found;
    }

    private static int column(Path file, List<String> header, String name) throws IOException {
        int index = header.stream().map(String::trim).toList().indexOf(name);
        if (index < 0) {
            throw new IOException(file + " is not an LGD district list: no \"" + name + "\" column");
        }
        return index;
    }

    /** RFC 4180 CSV: quoted fields may hold commas, quotes ("") and line breaks. */
    static List<List<String>> csv(Reader in) throws IOException {
        BufferedReader reader = new BufferedReader(in);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        int c;
        while ((c = reader.read()) != -1) {
            if (quoted) {
                if (c == '"') {
                    reader.mark(1);
                    if (reader.read() == '"') {
                        field.append('"');
                    } else {
                        reader.reset();
                        quoted = false;
                    }
                } else {
                    field.append((char) c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n') {
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else if (c != '\r' && c != '﻿') {
                field.append((char) c);
            }
        }
        if (!field.isEmpty() || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }
}
