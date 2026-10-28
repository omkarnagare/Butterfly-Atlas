package com.butterflyatlas.placeimporter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NamesTest {

    @Test
    void plainDropsDiacritics() {
        assertThat(Names.plain("Mahārāshtra")).isEqualTo("Maharashtra");
        assertThat(Names.plain("Jammu and Kashmīr")).isEqualTo("Jammu and Kashmir");
        assertThat(Names.plain("Pune")).isEqualTo("Pune");
    }

    @Test
    void keysMatchSpellingsThatDifferOnlyInCaseSpacingPunctuationAndALeadingThe() {
        assertThat(Names.key("The Dadra And Nagar Haveli And Daman And Diu"))
                .isEqualTo(Names.key("Dādra and Nagar Haveli and Damān and Diu"));
        assertThat(Names.key("Leh(Ladakh)")).isEqualTo(Names.key("Leh Ladakh"));
        assertThat(Names.key("S.A.S Nagar")).isEqualTo("sasnagar");
        assertThat(Names.key("Andaman & Nicobar")).isEqualTo("andamanandnicobar");
    }

    @Test
    void keysAlsoComeWithoutAndFromInsideParentheses() {
        assertThat(Names.keys("Khandwa (East Nimar)")).containsExactly("khandwaeastnimar", "khandwa", "eastnimar");
        assertThat(Names.keys("Pune")).containsExactly("pune");
    }

    @Test
    void tidyFixesCapitalsConnectorsAndSpacing() {
        assertThat(Names.tidy("MAUGANJ")).isEqualTo("Mauganj");
        assertThat(Names.tidy("North And Middle Andaman")).isEqualTo("North and Middle Andaman");
        assertThat(Names.tidy("Leh(Ladakh)")).isEqualTo("Leh (Ladakh)");
        assertThat(Names.tidy("  Kaimur   (Bhabua) ")).isEqualTo("Kaimur (Bhabua)");
        assertThat(Names.tidy("The Nilgiris")).isEqualTo("The Nilgiris");
        assertThat(Names.tidy("Isle Of Man")).isEqualTo("Isle of Man");
        assertThat(Names.tidy("Y.S.R. Kadapa")).isEqualTo("Y.S.R. Kadapa");
    }
}
