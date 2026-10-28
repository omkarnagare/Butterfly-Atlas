package com.butterflyatlas.app.exports;

import java.util.function.Consumer;

/** Test access to the export's hooks from other packages. */
public final class TestExports {

    private TestExports() {
    }

    /** Runs before each photo is copied. */
    public static void beforePhoto(Exports exports, Consumer<ExportStore.Item> hook) {
        exports.beforePhoto(hook);
    }

    /** Whether the export's copy is asked to stop and has not stopped yet. */
    public static boolean stopAsked(Exports exports, long id) {
        return exports.stopAsked(id);
    }
}
