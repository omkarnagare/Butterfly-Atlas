package com.butterflyatlas.app.imports;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Imports as the UI and {@code /api/status} show them: summaries with counts and progress, working and stopped ones. */
final class ImportSummaries {

    private static final ImportStore.Counts NO_ITEMS = new ImportStore.Counts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    private final ImportStore store;
    private final ImportJobs jobs;
    /** Imports whose last walk found their folder empty where photos still to file were: as good as missing. */
    private final Set<Long> emptied;
    /** How many of an import's HEIC previews are being made now. */
    private final java.util.function.LongToIntFunction heicMaking;
    /** An import's revision (multi-window contract §5). */
    private final java.util.function.LongUnaryOperator revision;

    ImportSummaries(ImportStore store, ImportJobs jobs, Set<Long> emptied, java.util.function.LongToIntFunction heicMaking,
                    java.util.function.LongUnaryOperator revision) {
        this.store = store;
        this.jobs = jobs;
        this.emptied = emptied;
        this.heicMaking = heicMaking;
        this.revision = revision;
    }

    /** Every import, open ones first, newest first (their counts in one query). */
    List<Imports.Summary> list() {
        Map<Long, ImportStore.Counts> counts = store.countsByRun();
        return store.runs().stream().map(run -> summary(run, counts.getOrDefault(run.id(), NO_ITEMS))).toList();
    }

    Imports.Summary summary(long id) {
        return summary(store.run(id));
    }

    /** The import working now, if any (from memory). */
    Optional<Imports.Working> working() {
        return working(store.briefs());
    }

    /** The import working now among {@code briefs}, if any. */
    Optional<Imports.Working> working(List<ImportStore.Brief> briefs) {
        return jobs.current().flatMap(progress -> briefs.stream().filter(run -> run.id() == progress.runId())
                .findFirst().map(run -> new Imports.Working(run.id(), rootName(run.root()), progress.phase(),
                        progress.done(), progress.total())));
    }

    /** Open imports whose work the app's closing cut short, not continued since (from memory). */
    List<Imports.Working> stopped() {
        return stopped(store.briefs());
    }

    /** The stopped imports among {@code briefs}. */
    List<Imports.Working> stopped(List<ImportStore.Brief> briefs) {
        return briefs.stream().filter(run -> run.open() && run.stopped() != null)
                .map(run -> new Imports.Working(run.id(), rootName(run.root()), run.stopped().phase(),
                        run.stopped().done(), run.stopped().total()))
                .toList();
    }

    Imports.Summary summary(ImportStore.Run run) {
        return summary(run, store.counts(run.id()));
    }

    private Imports.Summary summary(ImportStore.Run run, ImportStore.Counts counts) {
        Optional<ImportJobs.Progress> progress = jobs.of(run.id());
        boolean missing = !Files.isDirectory(run.root()) || emptied.contains(run.id());
        return new Imports.Summary(run.id(), run.rootPath(), rootName(run), run.state(), run.createdAt(), run.updatedAt(),
                missing, run.paused(), progress.map(ImportJobs.Progress::phase).orElse(Imports.IDLE),
                progress.map(p -> new Imports.Progress(p.done(), p.total())).orElse(null), run.stopped(), run.notice(),
                run.updateNote(), counts, run.filings(), store.pairs(run.id()),
                store.heic(run.id(), heicMaking.applyAsInt(run.id())), revision.applyAsLong(run.id()), null);
    }

    private static String rootName(ImportStore.Run run) {
        return rootName(run.root());
    }

    private static String rootName(Path root) {
        return root.getFileName() == null ? root.toString() : root.getFileName().toString();
    }
}
