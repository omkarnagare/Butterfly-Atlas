package com.butterflyatlas.app.library.sync;

import java.util.List;

/**
 * Why a library was not opened on this device (one-device contract §1): the setup screen shows a page for each, in place
 * of the folder chooser. The fields are what {@code GET /api/setup} answers (times as ISO-8601 instants).
 */
public sealed class Refusal extends RuntimeException {

    private Refusal(String message) {
        this(message, null);
    }

    private Refusal(String message, Throwable cause) {
        super(message, cause);
    }

    /** Spec §4 case 2: another device has it open ({@code name} null when unknown). */
    public static final class OtherDevice extends Refusal {
        public record Info(String name, String seen, String openAnywayFrom) {
        }

        private final Info info;

        OtherDevice(Info info) {
            super("The library is open on another device");
            this.info = info;
        }

        public Info info() {
            return info;
        }
    }

    /** Spec §5 case 1: the marker came through before the database. */
    public static final class NotSynced extends Refusal {
        public record Info(String folder) {
        }

        private final Info info;

        NotSynced(Info info) {
            super("The library's database hasn't finished syncing to this device");
            this.info = info;
        }

        public Info info() {
            return info;
        }
    }

    /**
     * Spec §7: this device's unsent changes can't be sent. {@code by} and {@code at} name the device that used the library
     * since (null when unknown), {@code since} is when this device was last in step; {@code photos} lists up to 20 photos in
     * the library's folder that the snapshot doesn't know, {@code photosMore} counts the rest.
     */
    public static final class Discarded extends Refusal {
        public record Info(String by, String at, String since, List<String> photos, int photosMore) {
        }

        private final Info info;

        Discarded(Info info) {
            super("This device's unsent changes can't be sent");
            this.info = info;
        }

        public Info info() {
            return info;
        }
    }

    /** A step of claiming failed; the message is the setup screen's words. */
    public static final class Failed extends Refusal {
        Failed(String words, Throwable cause) {
            super(words, cause);
        }
    }
}
