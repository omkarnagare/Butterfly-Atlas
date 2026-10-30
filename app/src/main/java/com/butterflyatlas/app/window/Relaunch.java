package com.butterflyatlas.app.window;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

/**
 * How the app reopens itself once a change of library folder is saved (change-library-folder contract §1, spec §6): the
 * installed app's own program ({@code jpackage.app-path}, which the packaged launcher sets on macOS, Windows and Linux) is
 * started again with no arguments, after the quit has begun and before the exit (restart). Without that program (a
 * development run) the app quits and asks the user to open it again, and says why.
 */
public final class Relaunch {

    /** The system property the packaged launcher sets to its own program. */
    public static final String APP_PATH = "jpackage.app-path";

    /** Why the app quits and asks instead of reopening: it was not started from its installed app. */
    public static final String NOT_INSTALLED = "This copy of Butterfly Atlas wasn't started from its installed app, so it"
            + " can't reopen itself.";
    /** Why the app quits and asks after all: its program would not start. */
    public static final String NOT_STARTED = "Butterfly Atlas couldn't reopen itself.";

    /** The command that restarts the app, or (null) why it quits and asks instead. */
    public record Plan(List<String> command, String why) {

        /** Whether the app starts itself again (else it quits and asks). */
        public boolean restarts() {
            return command != null;
        }
    }

    /**
     * What a library change calls to end this copy of the app (set once the app runs): how it will reopen, the quit begun
     * and the new copy started, then the exit.
     */
    public interface Reopener {

        /** How the app will reopen. */
        Plan plan();

        /**
         * Begins the quit (a launch from now on starts a new copy) and starts the new copy when {@code plan} restarts;
         * false when it did not start one (none to start, or the start failed: logged).
         */
        boolean begin(Plan plan);

        /** The exit, on a thread of its own (the quit's hooks run meanwhile). */
        void exit();
    }

    private Relaunch() {
    }

    /** The plan for the installed app's program at {@code appPath} (null or missing: quit and ask). */
    public static Plan plan(String appPath) {
        if (appPath == null || appPath.isBlank()) {
            return new Plan(null, NOT_INSTALLED);
        }
        Path program;
        try {
            program = Path.of(appPath);
        } catch (InvalidPathException e) {
            return new Plan(null, NOT_INSTALLED);
        }
        if (!program.isAbsolute() || !Files.isRegularFile(program)) {
            return new Plan(null, NOT_INSTALLED);
        }
        return new Plan(List.of(program.toString()), null);
    }
}
