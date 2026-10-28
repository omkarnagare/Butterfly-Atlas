package com.butterflyatlas.app.imports;

import com.butterflyatlas.app.search.SearchService;
import com.butterflyatlas.app.search.SearchText;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Species suggested from folder and file names (design §4.2 step 3, B6), through the existing search. A name is
 * cleaned (separators become spaces; camera prefixes such as {@code IMG_} or {@code DSC_} and any token with a digit at the start or
 * end, and a camera prefix left at the end, are dropped) and suggests a species only when exactly one species matches it exactly, by scientific name, common
 * name or one of the user's search words; a trailing "butterfly" (or "butterflies") counts on neither side, as the shipped common names end
 * with it ("Plain Tiger Butterfly") and folder names rarely do. When nothing matches exactly, a common name without its
 * last word (a group word such as "Swallowtail") counts, if at least two words remain ("Common Mormon" for "Common Mormon
 * Swallowtail"), again only when exactly one species matches. A name with a digit left inside it (a screenshot or phone name) is never searched, so a user search word containing digits never suggests. Synonyms and other partial matches never suggest. One instance remembers each
 * answer, so a walk of thousands of files searches once per distinct name; the cache lives as long as the instance (search words added later are not seen by it); it is meant for one thread at a time.
 */
public final class Suggestions {

    private static final Pattern SEPARATORS = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern DIGIT = Pattern.compile("\\p{N}");
    /** A camera's file-name prefix, alone or run into its number ({@code IMG}, {@code DSC0412}, {@code P1010001}). */
    private static final Pattern CAMERA_PREFIX = Pattern.compile(
            "(img|dsc|dscn|dscf|dcp|cimg|pict|imag|pxl|mvimg|gopr|gp|dji|sam|p|photo|image)\\p{N}*");
    private static final Set<String> MATCH_KINDS = Set.of("name", "common", "word");
    /** Trailing words that count on neither side ("Plain Tiger Butterfly", a folder "Plain tiger butterflies"). */
    private static final List<String> GROUP_WORDS = List.of("butterfly", "butterflies");

    private final SearchService search;
    private final Map<String, Optional<String>> answers = new HashMap<>();

    public Suggestions(SearchService search) {
        this.search = search;
    }

    /**
     * The suggestion for a file: from its own name first, then from each folder above it (nearest first), then from the
     * import's folder name; empty when none of them names exactly one species.
     */
    public Optional<String> forFile(String rootName, String relPath) {
        List<String> names = new ArrayList<>();
        String[] parts = relPath.split("/");
        names.add(stem(parts[parts.length - 1]));
        for (int i = parts.length - 2; i >= 0; i--) {
            names.add(parts[i]);
        }
        if (rootName != null) {
            names.add(rootName);
        }
        for (String name : names) {
            Optional<String> species = forName(name);
            if (species.isPresent()) {
                return species;
            }
        }
        return Optional.empty();
    }

    /** The one species a folder or file name (without extension) names exactly, if any. */
    public Optional<String> forName(String name) {
        String cleaned = clean(name);
        if (cleaned.isEmpty() || hasDigit(cleaned)) {
            return Optional.empty();
        }
        String wanted = comparable(cleaned);
        if (wanted.isEmpty()) {
            return Optional.empty();
        }
        return answers.computeIfAbsent(wanted, this::lookUp);
    }

    /** A name as compared: normalised, without a trailing "butterfly" or "butterflies". */
    static String comparable(String name) {
        String normal = SearchText.normalize(name);
        for (String word : GROUP_WORDS) {
            if (normal.equals(word)) {
                return "";
            }
            if (normal.endsWith(" " + word)) {
                return normal.substring(0, normal.length() - word.length() - 1);
            }
        }
        return normal;
    }

    /** "Danaus_chrysippus_012" → "Danaus chrysippus"; "IMG_0412" → "". */
    static String clean(String name) {
        List<String> tokens = new ArrayList<>(Arrays.stream(SEPARATORS.split(name == null ? "" : name))
                .filter(t -> !t.isEmpty()).toList());
        if (!tokens.isEmpty() && CAMERA_PREFIX.matcher(tokens.get(0).toLowerCase(Locale.ROOT)).matches()) {
            tokens.remove(0);
        }
        boolean trimmed = true;
        while (trimmed) {
            trimmed = false;
            while (!tokens.isEmpty() && hasDigit(tokens.get(tokens.size() - 1))) {
                tokens.remove(tokens.size() - 1);
                trimmed = true;
            }
            if (!tokens.isEmpty() && CAMERA_PREFIX.matcher(tokens.get(tokens.size() - 1).toLowerCase(Locale.ROOT)).matches()) {
                tokens.remove(tokens.size() - 1);
                trimmed = true;
            }
        }
        while (!tokens.isEmpty() && hasDigit(tokens.get(0))) {
            tokens.remove(0);
        }
        return String.join(" ", tokens);
    }

    private static boolean hasDigit(String token) {
        return DIGIT.matcher(token).find();
    }

    private Optional<String> lookUp(String wanted) {
        List<SearchService.Hit> hits = search.search(wanted, SearchService.MAX_LIMIT).stream()
                .filter(hit -> "species".equals(hit.rank()) && hit.active() && MATCH_KINDS.contains(hit.matchKind()))
                .toList();
        List<String> exact = hits.stream().filter(hit -> comparable(hit.matched()).equals(wanted))
                .map(SearchService.Hit::id).distinct().toList();
        if (!exact.isEmpty()) {
            return exact.size() == 1 ? Optional.of(exact.get(0)) : Optional.empty();
        }
        List<String> shortened = hits.stream().filter(hit -> "common".equals(hit.matchKind())
                        && wanted.equals(withoutLastWord(comparable(hit.matched()))))
                .map(SearchService.Hit::id).distinct().toList();
        return shortened.size() == 1 ? Optional.of(shortened.get(0)) : Optional.empty();
    }

    /** "common mormon swallowtail" → "common mormon"; null when fewer than two words would remain. */
    static String withoutLastWord(String normalised) {
        String[] words = normalised.split(" ");
        return words.length < 3 ? null : String.join(" ", Arrays.copyOf(words, words.length - 1));
    }

    private static String stem(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }
}
