package com.butterflyatlas.app.exports;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.ErrorCodes;
import com.butterflyatlas.app.filters.Filter;
import com.butterflyatlas.app.filters.FilterQuery.ExportPhoto;
import com.butterflyatlas.app.filters.FilterQuery.ExportSet;
import com.butterflyatlas.app.filters.FilterQuery;
import com.butterflyatlas.app.library.Library;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/**
 * The checks and the start of an export (4b contract §2 Check, Start): the refusals in the contract's order, the free
 * space, the names; then in one go the export folder created (atomically, {@code _2} on a clash), the CSV written from
 * the same snapshot as the photo list, and the run with every item recorded in one write, whose undo removes the
 * folder and the CSV again.
 */
public final class ExportStart {

    private static final Logger LOG = LoggerFactory.getLogger(ExportStart.class);

    /** Room kept free beyond the photos: max(200 MB, 1 %). */
    static final long MARGIN = 200L * 1024 * 1024;
    /** How many numbered names are tried for the export folder or the CSV. */
    private static final int MAX_TRIES = 10_000;

    /**
     * What the user chose, checked. {@code depth}: 0 none, 1 country, 2 state, 3 district; {@code rawWithLocation}: RAW
     * and HEIC photos may go out exactly, with any location they hold; {@code acceptedKinds}: which kinds the tick named
     * (Phase 7b audit 17), null for "whatever the export has".
     */
    record Choices(String what, String layout, boolean stripLocation, String placeDepth, int depth, boolean rawWithLocation,
                   List<String> acceptedKinds) {

        boolean photos() {
            return !Exports.CSV.equals(what);
        }

        boolean csv() {
            return !Exports.PHOTOS.equals(what);
        }
    }

    /**
     * What a check found; {@code folder} is the chosen folder once it is known to be one, {@code problem} null if none;
     * for a start with photos, their {@code targets} in the export folder {@code name}.
     */
    record Plan(ExportSet set, Path folder, String name, long needed, long free, Exports.Refusal problem,
                List<ExportNames.Target> targets) {
    }

    /** Writes the species list (tests make it fail). */
    @FunctionalInterface
    interface CsvWriter {
        void write(OutputStream out, List<FilterQuery.ListRow> rows, int depth, boolean missing) throws IOException;
    }

    private final Supplier<Library> library;
    private final FilterQuery filters;
    private final ExportStore store;
    private final Clock clock;
    private final ZoneId zone;
    private final ToLongFunction<Path> usableSpace;
    /** Puts a written file in place without ever replacing one (the copy's {@link ExportCopier#place}). */
    private final ExportCopier.Placer placer;
    volatile CsvWriter csvWriter = SpeciesCsv::write;
    /** Runs just before the export folder is created, with its path (tests make it taken there). */
    volatile Consumer<Path> beforeFolder = p -> { };
    /** Runs inside the start's write, before the run is recorded (tests make it fail). */
    volatile Runnable beforeRecord = () -> { };
    /** Runs once the start's write has committed (tests make it fail). */
    volatile Runnable afterRecord = () -> { };

    ExportStart(Supplier<Library> library, FilterQuery filters, ExportStore store, Clock clock, ZoneId zone,
                ToLongFunction<Path> usableSpace, ExportCopier.Placer placer) {
        this.library = library;
        this.placer = placer;
        this.filters = filters;
        this.store = store;
        this.clock = clock;
        this.zone = zone;
        this.usableSpace = usableSpace;
    }

    /** The sheet's choices checked (400 {@code invalid} with the contract's words), with the defaults for those not given. */
    static Choices choices(String what, String layout, Boolean stripLocation, String placeDepth, boolean rawWithLocation,
                           List<String> acceptedKinds) {
        if (what == null || !List.of(Exports.PHOTOS, Exports.CSV, Exports.BOTH).contains(what)) {
            throw ExportErrors.invalid("Unknown export “" + (what == null ? "" : what) + "”.");
        }
        String l = layout == null ? Exports.SPECIES : layout;
        if (!List.of(Exports.SPECIES, Exports.FLAT).contains(l)) {
            throw ExportErrors.invalid("Unknown layout “" + layout + "”.");
        }
        String d = placeDepth == null ? Exports.DEPTHS.get(0) : placeDepth;
        int depth = Exports.DEPTHS.indexOf(d);
        if (depth < 0) {
            throw ExportErrors.invalid("Unknown place depth “" + placeDepth + "”.");
        }
        if (acceptedKinds != null) {
            for (String kind : acceptedKinds) {
                if (!List.of("raw", "heic").contains(kind)) {
                    throw ExportErrors.invalid("Unknown file type “" + kind + "”.");
                }
            }
        }
        return new Choices(what, l, stripLocation == null || stripLocation, d, depth, rawWithLocation,
                acceptedKinds == null ? null : List.copyOf(acceptedKinds));
    }

    /** The check (the sheet's space line): read-only; it reads no photo list, so path lengths are the start's. */
    Exports.Check check(Filter filter, Choices choices, String rawPath) {
        Plan plan = plan(filter, choices, rawPath, false, today());
        ExportSet set = plan.set();
        return new Exports.Check(set.photos(), species(set, choices), set.bytes(), plan.needed(), plan.free(), plan.name(),
                plan.problem(), new Exports.Raw(set.rawPhotos(), set.rawWithGps(), set.rawWithUnknown()),
                new Exports.Raw(set.heicPhotos(), set.heicWithGps(), set.heicWithUnknown()));
    }

    /** Starts an export; the caller holds the start lock and has checked that none is running or paused. */
    long start(Filter filter, Choices choices, String rawPath) {
        LocalDate day = today();
        Plan plan = plan(filter, choices, rawPath, true, day);
        if (plan.problem() != null) {
            throw ExportErrors.refused(plan.problem().code(), plan.problem().message());
        }
        ExportSet set = plan.set();
        Path dest = plan.folder();
        Path exportFolder = choices.photos() ? createFolder(dest, ExportNames.exportFolder(day)) : null;
        Path csv = null;
        boolean writing = false;
        try {
            List<ExportStore.NewItem> items = choices.photos() ? items(plan, choices, exportFolder) : List.of();
            if (choices.csv()) {
                csv = writeCsv(set, choices, filter.byStages(),
                        exportFolder != null ? exportFolder.resolve(ExportNames.CSV_IN_FOLDER) : null, dest, day);
            }
            String folderName = (exportFolder != null ? exportFolder : csv).getFileName().toString();
            BasicFileAttributes csvAttrs = csv == null ? null : attributes(csv);
            ExportStore.NewRun run = new ExportStore.NewRun(dest.toString(), folderName, choices.what(), choices.layout(),
                    choices.stripLocation(), choices.placeDepth(), set.query(),
                    csv == null ? null : csv.getFileName().toString(), species(set, choices),
                    choices.photos() ? ExportStore.RUNNING : ExportStore.DONE,
                    csvAttrs == null ? null : csvAttrs.size(),
                    csvAttrs == null ? null : csvAttrs.lastModifiedTime().toMillis(),
                    choices.photos() && choices.rawWithLocation(), accepted(set, choices));
            Path written = csv;
            // From here the write owns the files: its undo removes them when it does not commit (the work or the commit
            // failed, or no connection); a failure after the commit (closing the connection, afterRecord) leaves them,
            // as they belong to the recorded export.
            writing = true;
            long id = store.record(run, items, beforeRecord, () -> removeQuietly(written, exportFolder));
            afterRecord.run();
            return id;
        } catch (RuntimeException e) {
            if (!writing) {
                removeQuietly(csv, exportFolder); // a refusal or failure before the write: nothing of this export stays
            }
            throw e;
        }
    }

    /**
     * The kinds the tick accepted, kept on the run (review fix J5): those the page named, or, without that (older pages),
     * those the export has at its start; none without the tick.
     */
    private static List<String> accepted(ExportSet set, Choices choices) {
        if (!choices.photos() || !choices.rawWithLocation()) {
            return List.of();
        }
        if (choices.acceptedKinds() != null) {
            return choices.acceptedKinds().stream().distinct().toList();
        }
        List<String> kinds = new java.util.ArrayList<>();
        if (set.photoList().stream().anyMatch(ExportPhoto::raw)) {
            kinds.add("raw");
        }
        if (set.photoList().stream().anyMatch(ExportPhoto::heic)) {
            kinds.add("heic");
        }
        return kinds;
    }

    /** The names the species list takes in the export folder (with photos and the list together). */
    private static List<String> reserved(Choices choices) {
        return choices.csv() ? List.of(ExportNames.CSV_IN_FOLDER, ExportNames.CSV_IN_FOLDER + ExportNames.PART) : List.of();
    }

    /** The species of the sheet's count line: those with matching photos for photos only, else the list's species. */
    private static int species(ExportSet set, Choices choices) {
        return choices.csv() ? set.species() : set.photographedSpecies();
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), zone);
    }

    /**
     * The refusals in the contract's order, the free space and the name the export would have. For a start the photo
     * list and the species list are read in one snapshot, and the photos' targets made (refused when a path would be
     * too long); a check reads neither list.
     */
    private Plan plan(Filter filter, Choices choices, String rawPath, boolean forStart, LocalDate day) {
        ExportSet set = filters.exportSet(filter, choices.depth(), forStart && choices.csv(), forStart && choices.photos());
        Path folder = path(rawPath);
        if (folder == null) {
            return new Plan(set, null, null, 0, 0, refusal(ErrorCodes.INVALID, ExportErrors.NO_PATH), null);
        }
        if (!Files.exists(folder)) {
            return new Plan(set, null, null, 0, 0, refusal(ErrorCodes.NOT_FOUND, ExportErrors.NOT_FOUND), null);
        }
        if (!Files.isDirectory(folder)) {
            return new Plan(set, null, null, 0, 0, refusal(ErrorCodes.NOT_FOLDER, ExportErrors.NOT_FOLDER), null);
        }
        long margin = Math.max(MARGIN, set.bytes() / 100);
        long needed = choices.photos() ? set.bytes() + margin : 0;
        long free = usableSpace.applyAsLong(folder);
        String name = null;
        Exports.Refusal problem = null;
        List<ExportNames.Target> targets = null;
        if (isInside(folder, library.get().folder())) {
            problem = refusal(ErrorCodes.LIBRARY_FOLDER, ExportErrors.LIBRARY_FOLDER);
        } else if (!writable(folder)) {
            problem = refusal(ErrorCodes.NOT_WRITABLE, ExportErrors.NOT_WRITABLE);
        } else {
            Set<String> taken = entries(folder);
            name = choices.photos() ? ExportNames.firstFree(ExportNames.exportFolder(day), taken) : csvName(day, taken);
            if (choices.photos() && set.photos() == 0) {
                problem = refusal("nothing", ExportErrors.NO_PHOTOS);
            } else if (!choices.photos() && set.species() == 0) {
                problem = refusal("nothing", ExportErrors.NO_SPECIES);
            } else if (choices.photos() && free < needed) {
                problem = refusal(ErrorCodes.NO_SPACE, ExportErrors.noSpace(set.bytes(), margin, free));
            } else if (forStart && choices.photos()) {
                try {
                    targets = ExportNames.targets(folder.resolve(name).toString(), sources(set, choices),
                            reserved(choices), Exports.FLAT.equals(choices.layout()));
                } catch (AtlasException e) {
                    problem = refusal(e.code(), e.getMessage());
                }
            }
            // Checked last (7a contract §5): folder and space problems show first, the tick is the final step. The
            // photos listed at the start are the ones that count. The tick covers RAW and HEIC photos (Phase 7b contract
            // §6); a start whose exact photos include a kind its tick did not name is refused too (audit 17).
            if (problem == null && choices.photos()) {
                boolean raw = forStart ? set.photoList().stream().anyMatch(ExportPhoto::raw) : set.rawPhotos() > 0;
                boolean heic = forStart ? set.photoList().stream().anyMatch(ExportPhoto::heic) : set.heicPhotos() > 0;
                List<String> accepted = choices.acceptedKinds();
                boolean unseen = accepted != null && ((raw && !accepted.contains("raw")) || (heic && !accepted.contains("heic")));
                if ((raw || heic) && (!choices.rawWithLocation() || unseen)) {
                    problem = refusal("raw_location", ExportErrors.exactLocation(raw, heic));
                }
            }
        }
        return new Plan(set, folder, name, needed, free, problem, targets);
    }

    private static Exports.Refusal refusal(String code, String message) {
        return new Exports.Refusal(code, message);
    }

    /** The chosen folder as an absolute, normalised path; null when none is given (or it is not a full path). */
    private static Path path(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }
        try {
            Path path = Path.of(rawPath.strip());
            return path.isAbsolute() ? path.normalize() : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** Whether {@code path} is {@code folder} or inside it, on disk (links and letter case resolved). */
    public static boolean isInside(Path path, Path folder) {
        try {
            return path.toRealPath().startsWith(folder.toRealPath());
        } catch (IOException e) {
            return path.toAbsolutePath().normalize().startsWith(folder.toAbsolutePath().normalize());
        }
    }

    /** Whether a file can be created (and deleted) in the folder. */
    public static boolean writable(Path folder) {
        try {
            Path probe = Files.createTempFile(folder, ".butterfly-atlas-", ".tmp");
            Files.delete(probe);
            return true;
        } catch (IOException | RuntimeException e) {
            LOG.debug("Cannot write to {}", folder, e);
            return false;
        }
    }

    /** The names in a folder (lower case); empty when it cannot be listed. */
    public static Set<String> entries(Path folder) {
        Set<String> names = new HashSet<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
            for (Path entry : entries) {
                names.add(ExportNames.lower(entry.getFileName().toString()));
            }
        } catch (IOException | DirectoryIteratorException e) {
            LOG.debug("Could not list {}", folder, e);
        }
        return names;
    }

    /** The first free name of the CSV on its own: neither it nor its {@code .part} taken (without case). */
    private static String csvName(LocalDate day, Set<String> taken) {
        String base = ExportNames.csvFile(day);
        for (int n = 1; n <= MAX_TRIES; n++) {
            String name = ExportNames.numbered(base, n);
            if (!taken.contains(ExportNames.lower(name)) && !taken.contains(ExportNames.lower(name + ExportNames.PART))) {
                return name;
            }
        }
        throw ExportErrors.refused(ErrorCodes.NOT_WRITABLE, ExportErrors.NOT_WRITABLE);
    }

    /** Creates the export folder atomically: the first numbered name not taken (without case) that it can create. */
    private Path createFolder(Path dest, String base) {
        Set<String> taken = entries(dest);
        for (int n = 1; n <= MAX_TRIES; n++) {
            String name = ExportNames.numbered(base, n);
            if (taken.contains(ExportNames.lower(name))) {
                continue;
            }
            Path folder = dest.resolve(name);
            beforeFolder.accept(folder);
            try {
                return Files.createDirectory(folder);
            } catch (FileAlreadyExistsException e) {
                taken.add(ExportNames.lower(name)); // made meanwhile: take the next
            } catch (NoSuchFileException e) {
                LOG.info("The chosen folder {} is gone", dest);
                throw ExportErrors.refused(ErrorCodes.NOT_FOUND, ExportErrors.NOT_FOUND);
            } catch (IOException e) {
                LOG.info("Could not create the export folder {}: {}", folder, e.toString());
                throw ExportErrors.refused(ErrorCodes.NOT_WRITABLE, ExportErrors.NOT_WRITABLE);
            }
        }
        throw ExportErrors.refused(ErrorCodes.NOT_WRITABLE, ExportErrors.NOT_WRITABLE);
    }

    private static List<ExportNames.Source> sources(ExportSet set, Choices choices) {
        List<ExportNames.Source> sources = new ArrayList<>(set.photoList().size());
        for (ExportPhoto p : set.photoList()) {
            sources.add(new ExportNames.Source(p.id(), p.fileName(), Exports.SPECIES.equals(choices.layout())
                    ? ExportNames.speciesFolders(p.family(), p.subfamily(), p.tribe(), p.genus(), p.species(), p.custom()) : List.of()));
        }
        return sources;
    }

    /**
     * The items with their targets in the export folder actually created: the plan's when it has the planned name,
     * made again when it gained a number meanwhile.
     */
    private static List<ExportStore.NewItem> items(Plan plan, Choices choices, Path exportFolder) {
        ExportSet set = plan.set();
        List<ExportNames.Target> targets = exportFolder.getFileName().toString().equals(plan.name()) ? plan.targets()
                : ExportNames.targets(exportFolder.toString(), sources(set, choices), reserved(choices),
                        Exports.FLAT.equals(choices.layout()));
        List<ExportStore.NewItem> items = new ArrayList<>(targets.size());
        for (int i = 0; i < targets.size(); i++) {
            ExportPhoto p = set.photoList().get(i);
            ExportNames.Target t = targets.get(i);
            items.add(new ExportStore.NewItem(p.id(), p.taxonId(), p.fileName(), t.rel(), t.renamed(), p.bytes(), p.raw(),
                    p.heic()));
        }
        return items;
    }

    /**
     * Writes the species list to {@code <name>.part} (never over an existing file) and puts it in place as the copy does,
     * never replacing anything: at {@code target} (in the new export folder), or as the first free numbered CSV in the
     * chosen folder (refused {@code path_too_long} when its path with {@code .part} would pass 259 characters). The
     * {@code .part} is removed on any failure.
     */
    private Path writeCsv(ExportSet set, Choices choices, boolean missing, Path target, Path dest, LocalDate day) {
        for (int n = 1; n <= MAX_TRIES; n++) {
            Path csv = target != null ? target : dest.resolve(csvName(day, entries(dest)));
            Path part = csv.resolveSibling(csv.getFileName() + ExportNames.PART);
            if (part.toString().length() > ExportNames.MAX_PATH) {
                // only a list on its own can get here: one in the export folder fits (its targets checked it)
                throw target == null ? ExportErrors.listPathTooLong()
                        : ExportErrors.pathTooLong(Exports.FLAT.equals(choices.layout()));
            }
            OutputStream out;
            try {
                out = Files.newOutputStream(part, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException e) {
                if (target != null) {
                    break;
                }
                continue; // made meanwhile: take the next name
            } catch (IOException e) {
                LOG.info("Could not write the species list {}: {}", csv, e.toString());
                break;
            }
            try {
                try (out) {
                    csvWriter.write(out, set.rows(), choices.depth(), missing);
                }
                if (placer.place(part, csv)) {
                    deleteQuietly(part); // linked, but its .part could not be removed: once more, else it stays
                }
                return csv;
            } catch (RuntimeException | Error e) {
                deleteQuietly(part);
                throw e;
            } catch (FileAlreadyExistsException e) {
                deleteQuietly(part);
                if (target != null) {
                    break;
                }
            } catch (IOException e) {
                deleteQuietly(part);
                LOG.info("Could not write the species list {}: {}", csv, e.toString());
                break;
            }
        }
        throw ExportErrors.refused(ErrorCodes.NOT_WRITABLE, ExportErrors.NOT_WRITABLE);
    }

    private static BasicFileAttributes attributes(Path file) {
        try {
            return Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            return null;
        }
    }

    /** Removes the CSV this start wrote and its export folder if that is then empty (never anything else). */
    private static void removeQuietly(Path csv, Path exportFolder) {
        deleteQuietly(csv);
        deleteQuietly(exportFolder);
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.warn("Could not remove {}", file, e);
        }
    }
}
