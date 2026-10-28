package com.butterflyatlas.app;

import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.testing.TestServices;
import com.butterflyatlas.app.web.AtlasServices;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** When a library opens, a backup the app's closing cut short is paused as closed, and nothing starts it again. */
class MainBackupStartTest {

    @TempDir
    Path home;

    @Test
    void aBackupLeftRunningIsPausedClosedAtTheNextStartAndTheStartupWorkStillRuns() throws Exception {
        AtlasServices services = TestServices.configured(home);
        try {
            Database db = Database.open(home.resolve("Atlas").resolve(Database.FILE_NAME));
            db.write(c -> {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO backup_run (state, what, dest_root, folder, total,"
                        + " bytes_total, started_at) VALUES ('running', 'everything', ?, 'Butterfly_Atlas_backup_2026-10-20', 5,"
                        + " 500, '2026-10-20T08:00:00Z')")) {
                    ps.setString(1, home.resolve("Backup").toString());
                    ps.executeUpdate();
                }
                return null;
            });
            List<String> seen = new ArrayList<>();

            Main.opened(services.updates(), services.imports(), services.exports(), services.backups(),
                    () -> seen.add("work"), () -> seen.add("settled"));

            assertThat(seen).containsExactly("work");
            assertThat(services.backups().current()).hasValueSatisfying(backup -> {
                assertThat(backup.state()).isEqualTo("paused");
                assertThat(backup.pausedBy()).isEqualTo("closed");
            });
            assertThat(services.backups().copying()).isFalse();
        } finally {
            services.imports().close();
            services.exports().close();
            services.backups().close();
            services.thumbnails().close();
        }
    }
}
