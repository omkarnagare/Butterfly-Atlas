package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.AtlasException;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A tag's name in its one form (tag-names design §2): words of letters and digits joined by {@code _}, each in lower
 * case unless it is an abbreviation (2 or more letters, all capitals: DSF, WSF2), at most 3 words and 40 characters.
 * Used by {@link TagService} and the V15 migration; {@code ui/src/lib/tags.js} has the same rule for the field's preview.
 */
public final class TagNames {

    public static final int MAX_LENGTH = 40;
    public static final int MAX_WORDS = 3;
    public static final String EMPTY = "Type a name for the tag.";
    public static final String TOO_MANY_WORDS = "Keep tag names to " + MAX_WORDS + " words or fewer.";
    public static final String TOO_LONG = "Keep tag names to " + MAX_LENGTH + " characters or fewer.";

    private static final Pattern NOT_ALPHANUMERIC = Pattern.compile("[^\\p{L}\\p{N}]+");

    private TagNames() {
    }

    /** The name in its form ("Mud-puddling on DSF" → "mud_puddling_on_DSF"); empty when nothing is left. */
    public static String form(String raw) {
        if (raw == null) {
            return "";
        }
        String joined = NOT_ALPHANUMERIC.matcher(Normalizer.normalize(raw, Normalizer.Form.NFC)).replaceAll("_");
        StringBuilder name = new StringBuilder();
        for (String word : joined.split("_")) {
            if (!word.isEmpty()) {
                name.append(name.isEmpty() ? "" : "_").append(abbreviation(word) ? word : word.toLowerCase(Locale.ROOT));
            }
        }
        return name.toString();
    }

    /** How many words a formed name has. */
    public static int words(String name) {
        return name.isEmpty() ? 0 : name.split("_", -1).length;
    }

    /** A new name or a rename in its form, or why it cannot be one. */
    public static String check(String raw) {
        String name = form(raw);
        if (name.isEmpty()) {
            throw AtlasException.invalid(EMPTY);
        }
        if (words(name) > MAX_WORDS) {
            throw AtlasException.invalid(TOO_MANY_WORDS);
        }
        if (name.length() > MAX_LENGTH) {
            throw AtlasException.invalid(TOO_LONG);
        }
        return name;
    }

    private static boolean abbreviation(String word) {
        long letters = word.codePoints().filter(Character::isLetter).count();
        return letters >= 2 && word.codePoints().filter(Character::isLetter).allMatch(Character::isUpperCase);
    }
}
