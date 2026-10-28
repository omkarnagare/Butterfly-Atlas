package com.butterflyatlas.app.search;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Turns what the user typed into an FTS5 query, and texts into a form that can be compared. */
public final class SearchText {

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern SEPARATORS = Pattern.compile("[^\\p{L}\\p{N}]+");

    private static final Pattern QUERY_SEPARATORS = Pattern.compile("[^\\p{L}\\p{N}\\p{M}]+");

    private SearchText() {
    }

    /** Lower case, accents removed, every run of non-letters/digits becomes one space. */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String plain = MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("");
        return SEPARATORS.matcher(plain.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }

    /**
     * The typed words, lower case, with every run of anything but letters, digits and combining
     * marks turned into one space. Marks are kept (Devanagari vowel signs are marks); the FTS5
     * tokenizer folds Latin accents itself.
     */
    public static String queryWords(String query) {
        if (query == null) {
            return "";
        }
        String text = Normalizer.normalize(query, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        return QUERY_SEPARATORS.matcher(text).replaceAll(" ").strip();
    }

    /**
     * Each word as a quoted prefix term ({@code "peaco"*}), all required. Quoting makes FTS5
     * operators such as OR or NEAR plain words. Empty for queries under two letters or digits.
     */
    public static Optional<String> matchExpression(String query) {
        String words = queryWords(query);
        String letters = MARKS.matcher(words.replace(" ", "")).replaceAll("");
        if (letters.codePointCount(0, letters.length()) < 2) {
            return Optional.empty();
        }
        return Optional.of(Arrays.stream(words.split(" "))
                .map(word -> "\"" + word + "\"*")
                .collect(Collectors.joining(" ")));
    }
}
