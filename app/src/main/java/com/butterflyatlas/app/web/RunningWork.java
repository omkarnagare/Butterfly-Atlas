package com.butterflyatlas.app.web;

import com.butterflyatlas.app.JobStates;
import com.butterflyatlas.app.backup.Backups;
import com.butterflyatlas.app.exports.Exports;
import com.butterflyatlas.app.imports.Imports;
import com.butterflyatlas.app.photos.ConsumerRunner;
import com.butterflyatlas.app.updates.Updates;

import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The work that refuses a change of library folder while it runs (change-library-folder spec §4), in the words that name
 * it: an export or backup that is running, an import that is filing, a taxonomy update running or waiting, a place
 * update or photo details being worked out, or the startup work still going. Paused exports and backups and open imports
 * don't count, and nothing is done to them.
 */
public final class RunningWork {

    static final String EXPORT = "An export is running. Pause it or wait for it to finish, then try again.";
    static final String BACKUP = "A backup is running. Pause it or wait for it to finish, then try again.";
    static final String FILING = "An import is filing photos. Wait for it to finish, then try again.";
    static final String TAXONOMY = "A taxonomy update is running. Wait for it to finish, then try again.";
    static final String PLACES = "Place names are being updated. Wait for that to finish, then try again.";
    static final String DETAILS = "Photo details are being updated. Wait for that to finish, then try again.";
    static final String STARTUP = "Butterfly Atlas is still checking your library after opening it. Wait for that to"
            + " finish, then try again.";

    private RunningWork() {
    }

    /** What runs, from the services that hold it; {@code startup}: whether the startup work is still going. */
    public static Supplier<Optional<String>> of(AtlasServices services, BooleanSupplier startup) {
        return of(services.exports(), services.backups(), services.imports(), services.updates(), services.processing(),
                startup);
    }

    /** As {@link #of(AtlasServices, BooleanSupplier)}, from the services themselves (before they are gathered). */
    public static Supplier<Optional<String>> of(Exports exports, Backups backups, Imports imports, Updates updates,
                                                ConsumerRunner processing, BooleanSupplier startup) {
        return of(() -> exports.current().map(e -> JobStates.RUNNING.equals(e.state())).orElse(false),
                () -> backups.current().map(b -> JobStates.RUNNING.equals(b.state())).orElse(false),
                imports::filing,
                () -> updates.status().map(u -> JobStates.RUNNING.equals(u.state())
                        || JobStates.WAITING.equals(u.state())).orElse(false),
                () -> {
                    ConsumerRunner.Progress progress = processing.progress();
                    return progress == null ? null : progress.step();
                }, startup);
    }

    /** What runs, the first found in the order above; {@code processing}: the step photo details are at, else null. */
    static Supplier<Optional<String>> of(BooleanSupplier export, BooleanSupplier backup, BooleanSupplier filing,
                                         BooleanSupplier taxonomy, Supplier<String> processing, BooleanSupplier startup) {
        return () -> {
            if (export.getAsBoolean()) {
                return Optional.of(EXPORT);
            }
            if (backup.getAsBoolean()) {
                return Optional.of(BACKUP);
            }
            if (filing.getAsBoolean()) {
                return Optional.of(FILING);
            }
            if (taxonomy.getAsBoolean()) {
                return Optional.of(TAXONOMY);
            }
            String step = processing.get();
            if (step != null) {
                return Optional.of(ConsumerRunner.PLACES.equals(step) ? PLACES : DETAILS);
            }
            if (startup.getAsBoolean()) {
                return Optional.of(STARTUP);
            }
            return Optional.empty();
        };
    }
}
