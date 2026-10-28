package com.butterflyatlas.app.window;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A start that cannot go on (a damaged bundle, a library that cannot open or is too new, a taken port, an unwritable
 * folder), and a second launch whose running copy does not answer: said in plain words on a small local page opened in the
 * default browser, written to the log, and printed on the console. From an installed app's icon nobody sees the console
 * (audit 1), so the page is what the user sees. The caller then exits with {@link #EXIT_FAILED} or
 * {@link #EXIT_NOT_ANSWERING}.
 */
public final class StartupFailure {

    /** The start could not go on. */
    public static final int EXIT_FAILED = 1;
    /** A second launch whose running copy does not answer (contract §8). */
    public static final int EXIT_NOT_ANSWERING = 2;
    static final String PAGE = "startup_problem.html";

    /** What the page says: a title, what happened, and what to do. */
    public record Message(String title, String what, String todo) {
    }

    private StartupFailure() {
    }

    public static Message damagedBundle(String detail) {
        String reason = detail.strip().replaceAll("[.\\s]+$", "");
        return new Message("Butterfly Atlas could not start", "Butterfly Atlas's own data is damaged: " + reason + ".",
                "Install Butterfly Atlas again from the installer you downloaded.");
    }

    public static Message libraryCannotOpen(String folder, String reason) {
        return new Message("Butterfly Atlas could not open your library",
                "Butterfly Atlas could not open your library at " + folder + ". " + reason,
                "Fix that, then open Butterfly Atlas again. Nothing in your library was changed.");
    }

    /** Another copy has the library (the lock): the same words as a second launch. */
    public static Message libraryInUse(String folder) {
        return new Message("Butterfly Atlas is already running",
                "Butterfly Atlas is already running. Only one copy can run at a time. Another copy of Butterfly Atlas is using"
                        + " the library at " + folder + ".",
                "Quit the other copy, then open Butterfly Atlas again.");
    }

    /** A setting the app was started with is not usable (a port, a number of seconds). */
    public static Message badSetting(String reason) {
        return new Message("Butterfly Atlas could not start", reason,
                "Correct that setting, or remove it, then open Butterfly Atlas again.");
    }

    /** {@code reason} is the library-too-new sentence (it already says to install the newer version). */
    public static Message libraryTooNew(String reason) {
        return new Message("This library needs a newer Butterfly Atlas", reason,
                "Install the newer version of Butterfly Atlas, then open it again. Nothing in your library was changed.");
    }

    public static Message portTaken(int port) {
        return new Message("Butterfly Atlas could not start",
                "Another program is using port " + port + ", which Butterfly Atlas was told to use.",
                "Close that program, or start Butterfly Atlas without a fixed port, then open it again.");
    }

    public static Message folderNotWritable(String folder) {
        return new Message("Butterfly Atlas could not start",
                "Butterfly Atlas cannot save its settings in " + folder + ".",
                "Check that the folder exists and that your account may change it, then open Butterfly Atlas again.");
    }

    /** The window's start-up file cannot be written, so no window can be opened (never with the key in the command). */
    public static Message launchFolderNotWritable(String configFolder) {
        return new Message("Butterfly Atlas could not open its window",
                "Butterfly Atlas could not write the small file that opens its window, in " + configFolder + ".",
                "Check that the folder exists and that your account may change it, then open Butterfly Atlas again.");
    }

    public static Message unexpected() {
        return new Message("Butterfly Atlas could not start",
                "Butterfly Atlas hit an unexpected problem. The details are in the log file.",
                "Open Butterfly Atlas again. If it keeps happening, send the log file to the Butterfly Atlas developers.");
    }

    /** The pointer of another live copy was found where this one was about to write its own. */
    public static Message alreadyRunning() {
        return new Message("Butterfly Atlas is already running",
                "Butterfly Atlas is already running. Only one copy can run at a time.",
                "Use the window of the running copy. If you cannot find it, quit Butterfly Atlas from your computer's task"
                        + " manager or activity monitor, then open it again.");
    }

    /** A second launch whose window (or tab) could not be opened on the running copy (audit A14). */
    public static Message windowNotOpened() {
        return new Message("Butterfly Atlas couldn't open another window",
                "Butterfly Atlas is running, but couldn't open another window.",
                "Use one of its open windows, or use New window there.");
    }

    /** A second launch whose running copy does not answer. */
    public static Message notAnswering() {
        return new Message("Butterfly Atlas is already running",
                "Butterfly Atlas is already running. Only one copy can run at a time, and the running copy is not answering.",
                "Wait a moment and open Butterfly Atlas again. If it still does not open, quit the running copy from your"
                        + " computer's task manager or activity monitor, then open it again.");
    }

    /** The page's HTML: self-contained, the words escaped. */
    static String html(Message message) {
        return "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\"><title>" + escape(message.title())
                + "</title><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><style>"
                + "body{font:16px/1.5 system-ui,sans-serif;max-width:36rem;margin:12vh auto;padding:0 1rem;color:#222}"
                + "h1{font-size:1.4rem}p{margin:.8rem 0}@media(prefers-color-scheme:dark){body{background:#181818;color:#eee}}"
                + "</style></head><body><h1>" + escape(message.title()) + "</h1><p>" + escape(message.what()) + "</p><p>"
                + escape(message.todo()) + "</p></body></html>\n";
    }

    /**
     * Writes the page into the config folder, opens it with {@code openPage} (true when it could be opened), logs the
     * reason; when the page cannot be opened, the reason and the page's place go to the console. A config folder that cannot
     * be written is the very problem the page may have to tell, so the page then goes to the temp folder instead (an
     * owner-only file under a random name, never a predictable one) and is opened from there, and the console gets the
     * reason as well. Returns the page (null when it could not be written anywhere).
     */
    public static Path report(Message message, Path configDir, AtlasLog log, Predicate<URI> openPage) {
        return report(message, configDir, log, openPage, System.out::println);
    }

    public static Path report(Message message, Path configDir, AtlasLog log, Predicate<URI> openPage,
                              Consumer<String> console) {
        return report(message, configDir, log, openPage, console, Path.of(System.getProperty("java.io.tmpdir")));
    }

    /** As above, with the temp folder given (tests). */
    public static Path report(Message message, Path configDir, AtlasLog log, Predicate<URI> openPage,
                              Consumer<String> console, Path tempDir) {
        log.error(message.title() + ": " + message.what() + " " + message.todo(), null);
        Path page = configDir.resolve(PAGE);
        String words = message.title() + ". " + message.what() + " " + message.todo();
        try {
            Files.createDirectories(configDir);
            Files.writeString(page, html(message), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not write " + page, e);
            console.accept(words);
            page = temporaryPage(message, tempDir, log);
            if (page != null) {
                openPage.test(page.toUri());
            }
            return page;
        }
        if (!openPage.test(page.toUri())) {
            console.accept(words + " (This is also written in " + page + ".)");
        }
        return page;
    }

    /** The page in the temp folder: created new with a random name and owner-only permissions; null when it cannot be. */
    private static Path temporaryPage(Message message, Path tempDir, AtlasLog log) {
        Path page = null;
        try {
            page = Files.createTempFile(tempDir, "butterfly_atlas_problem_", ".html"); // owner-only on POSIX systems
            InstancePointer.narrowToOwner(page); // and on Windows
            Files.writeString(page, html(message), StandardCharsets.UTF_8);
            return page;
        } catch (IOException | RuntimeException e) {
            log.warn("Could not write a start-up page in " + tempDir, e);
            if (page != null) {
                try {
                    Files.deleteIfExists(page);
                } catch (IOException ignored) {
                    // nothing more to do with it
                }
            }
            return null;
        }
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
