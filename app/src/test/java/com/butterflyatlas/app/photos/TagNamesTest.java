package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.AtlasException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TagNamesTest {

    @Test
    void formsLowerCaseWordsJoinedByUnderscores() {
        assertThat(TagNames.form("upper wing")).isEqualTo("upper_wing");
        assertThat(TagNames.form("Unconfirmed")).isEqualTo("unconfirmed");
        assertThat(TagNames.form("Mud-puddling")).isEqualTo("mud_puddling");
        assertThat(TagNames.form("  egg ")).isEqualTo("egg");
        assertThat(TagNames.form("Unconfirmed (built-in)")).isEqualTo("unconfirmed_built_in");
        assertThat(TagNames.form("__a..b__")).isEqualTo("a_b");
        assertThat(TagNames.form("Mud puddling on DSF  wet sand")).isEqualTo("mud_puddling_on_DSF_wet_sand");
    }

    @Test
    void keepsAbbreviationsOfTwoOrMoreCapitals() {
        assertThat(TagNames.form("DSF")).isEqualTo("DSF");
        assertThat(TagNames.form("WSF2")).isEqualTo("WSF2");
        assertThat(TagNames.form("UV light")).isEqualTo("UV_light");
        assertThat(TagNames.form("A")).isEqualTo("a");
        assertThat(TagNames.form("iNat")).isEqualTo("inat");
        assertThat(TagNames.form("2B")).isEqualTo("2b");
        assertThat(TagNames.form("ÉTÉ")).isEqualTo("ÉTÉ");
        assertThat(TagNames.form("Été")).isEqualTo("été");
    }

    @Test
    void composesAccentsAndKeepsDigits() {
        assertThat(TagNames.form("Café 2")).isEqualTo("café_2");
        assertThat(TagNames.form("!!!")).isEmpty();
        assertThat(TagNames.form(null)).isEmpty();
    }

    @Test
    void countsWords() {
        assertThat(TagNames.words("egg")).isEqualTo(1);
        assertThat(TagNames.words("mud_puddling_on_wet_sand")).isEqualTo(5);
        assertThat(TagNames.words("")).isZero();
    }

    @Test
    void checksANewName() {
        assertThat(TagNames.check(" Mud puddling site ")).isEqualTo("mud_puddling_site");
        for (String bad : List.of("", "   ", "!!!", "-")) {
            assertThatThrownBy(() -> TagNames.check(bad)).isInstanceOfSatisfying(AtlasException.class,
                    e -> assertThat(e.getMessage()).isEqualTo("Type a name for the tag."));
        }
        assertThatThrownBy(() -> TagNames.check("mud puddling on sand")).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.getMessage()).isEqualTo("Keep tag names to 3 words or fewer."));
        assertThat(TagNames.check("a".repeat(40))).hasSize(40);
        assertThatThrownBy(() -> TagNames.check("a".repeat(41))).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.getMessage()).isEqualTo("Keep tag names to 40 characters or fewer."));
    }
}
