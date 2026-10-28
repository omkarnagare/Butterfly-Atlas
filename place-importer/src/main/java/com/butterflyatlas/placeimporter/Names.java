package com.butterflyatlas.placeimporter;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Place-name helpers: plain spellings, matching keys and tidy display names. */
public final class Names {

    private Names() {
    }

    /** The name without diacritics ("Mahārāshtra" → "Maharashtra"). */
    public static String plain(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    /** The name as search compares it: plain and lower case ("Krško" and "KRSKO" are both "krsko"). */
    public static String fold(String name) {
        return plain(name).toLowerCase(Locale.ROOT);
    }

    /** A matching key: plain, lower case, "&" read as "and", no leading "the", letters and digits only. */
    public static String key(String name) {
        String key = plain(name).toLowerCase(Locale.ROOT).replace("&", " and ").trim();
        if (key.startsWith("the ")) {
            key = key.substring(4);
        }
        return key.replaceAll("[^a-z0-9]", "");
    }

    /** The key of the whole name, then of the part before and the part inside parentheses, if any. */
    public static List<String> keys(String name) {
        Set<String> keys = new LinkedHashSet<>();
        keys.add(key(name));
        int open = name.indexOf('(');
        int close = name.indexOf(')', open + 1);
        if (open >= 0) {
            keys.add(key(name.substring(0, open)));
            keys.add(key(name.substring(open + 1, close < 0 ? name.length() : close)));
        }
        keys.remove("");
        return new ArrayList<>(keys);
    }

    /** Single spaces, a space before "(", words of an all-capitals name capitalised, " And "/" Of " in lower case. */
    public static String tidy(String name) {
        String tidy = name.trim().replaceAll("\\s+", " ").replaceAll("(\\S)\\(", "$1 (");
        if (!tidy.chars().anyMatch(Character::isLowerCase)) {
            StringBuilder words = new StringBuilder();
            for (String word : tidy.split(" ")) {
                if (!words.isEmpty()) {
                    words.append(' ');
                }
                words.append(word.isEmpty() ? word : word.charAt(0) + word.substring(1).toLowerCase(Locale.ROOT));
            }
            tidy = words.toString();
        }
        return tidy.replace(" And ", " and ").replace(" Of ", " of ");
    }
}
