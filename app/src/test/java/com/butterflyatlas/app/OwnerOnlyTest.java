package com.butterflyatlas.app;

import org.junit.jupiter.api.Test;

import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;

/** What only this user may read stays only theirs: nothing for the group or others. */
class OwnerOnlyTest {

    @Test
    void foldersAndFilesAreTheOwnersAlone() {
        assertThat(OwnerOnly.POSIX).isEqualTo("posix");
        assertThat(PosixFilePermissions.fromString(OwnerOnly.FOLDER)).containsExactlyInAnyOrder(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
        assertThat(PosixFilePermissions.fromString(OwnerOnly.FILE)).containsExactlyInAnyOrder(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }
}
