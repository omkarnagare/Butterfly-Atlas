package com.butterflyatlas.app.photos;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FileNamesTest {

    @Test
    void spacesBecomeUnderscores() {
        assertThat(FileNames.safe("Danaus chrysippus [A5]")).isEqualTo("Danaus_chrysippus_[A5]");
        assertThat(FileNames.safe("My additions")).isEqualTo("My_additions");
    }

    @Test
    void charactersWindowsCannotStoreAreReplaced() {
        assertThat(FileNames.safe("a<b>c:d\"e/f\\g|h?i*j")).isEqualTo("a_b_c_d_e_f_g_h_i_j");
        assertThat(FileNames.safe("tab\there\u0001")).isEqualTo("tab_here_");
    }

    @Test
    void trailingDotsAreDroppedAndSpacesBecomeUnderscores() {
        assertThat(FileNames.safe("Papilio sp.")).isEqualTo("Papilio_sp");
        assertThat(FileNames.safe("Name ")).isEqualTo("Name_");
        assertThat(FileNames.safe("...")).isEqualTo("_");
        assertThat(FileNames.safe("")).isEqualTo("_");
    }

    @Test
    void windowsDeviceNamesGetAnUnderscore() {
        assertThat(FileNames.safe("CON")).isEqualTo("CON_");
        assertThat(FileNames.safe("aux.jpg")).isEqualTo("aux_.jpg");
        assertThat(FileNames.safe("Console")).isEqualTo("Console");
    }

    @Test
    void isIdempotentAndKeepsOtherLettersAndHyphens() {
        String once = FileNames.safe("Tirumala limniace-x [U7]");

        assertThat(once).isEqualTo("Tirumala_limniace-x_[U7]");
        assertThat(FileNames.safe(once)).isEqualTo(once);
        assertThat(FileNames.safe("तितली")).isEqualTo("तितली");
    }

    @Test
    void photoNamesUseTheScientificNameAndAtLeastTwoDigits() {
        assertThat(FileNames.photoName("Danaus chrysippus", 1, "jpg")).isEqualTo("Danaus_chrysippus_01.jpg");
        assertThat(FileNames.photoName("Danaus chrysippus", 7, "png")).isEqualTo("Danaus_chrysippus_07.png");
        assertThat(FileNames.photoName("Danaus chrysippus", 100, "tif")).isEqualTo("Danaus_chrysippus_100.tif");
    }
}
