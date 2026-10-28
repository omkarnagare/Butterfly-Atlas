package com.butterflyatlas.app.updates;

import com.butterflyatlas.app.photos.Photo;
import com.butterflyatlas.app.taxa.TaxonViews.Count;

import java.util.List;

/** What the {@code updates} package hands to the UI (5b contract §5). */
public final class UpdateViews {

    private UpdateViews() {
    }

    /** A taxon by name. */
    public record Ref(String id, String name, String common) {
    }

    /**
     * The taxonomy update as the update screen shows it: {@code state} none, unavailable, pending, waiting, running,
     * paused or done; {@code kind} bundle or match (null unless running, paused or done); {@code later}: Later was chosen
     * in this run; {@code summary} while pending ({@code summaryProblem} instead when the dry run could not be worked
     * out: the update can still be applied); {@code progress} while waiting, running or paused; {@code notice} while
     * paused or unavailable; {@code result} once done in this run ({@code resultProblem} instead when it could not be
     * read: the update is done all the same); {@code last} the most recent finished update's or merge's result, in every
     * state (after a restart too; {@code lastProblem} instead when it could not be read).
     */
    public record Update(String state, String kind, boolean later, String from, String to, List<String> steps,
                         Summary summary, String summaryProblem, Progress progress, Notice notice, Result result,
                         String resultProblem, Result last, String lastProblem) {
    }

    /** Before and after the update, for the species line ("342 of 1,139 species → 342 of 1,141"). */
    public record Species(Count before, Count after) {
    }

    /**
     * The dry run over the whole chain: {@code photos} live photos whose path, species or listing changes (split photos,
     * and photos of a species removed from the list or back on it);
     * {@code kinds} in the order rename, move, merge, split, add, remove (empty ones left out).
     */
    public record Summary(int photos, Species species, List<Kind> kinds) {
    }

    /** One kind of change: how many items and photos; items with photos first (most first), then by name. */
    public record Kind(String kind, int count, int photos, List<Item> items) {
    }

    /** One change (one per id and kind over the chain): the taxa before and after, the user's photos it affects. */
    public record Item(String rank, List<Ref> from, List<Ref> to, int photos, String detail) {
    }

    /** Where the running update is: step of steps, its stage, moves done of total. */
    public record Progress(int step, int steps, String stage, int done, int total, boolean continuing, String waitingFor) {
    }

    public record Notice(String code, String message) {
    }

    /**
     * A finished update or merge (5b contract §5, §7): the photos whose path, species or listing changed ({@code photos},
     * "affected"); the photo files actually moved ({@code moved}: a species only removed moves none); folders kept
     * because they hold other files; emptied folders that could not be removed; photos left behind (gone from their old
     * and new folder); splits and matches; plain one-line notes of what it did ("Photos still to sort on Danaus
     * chrysippus moved to Danaus genutia with the merge."; [] when none); when it finished. Kept in {@code change_log}.
     */
    public record Result(String from, String to, int photos, int moved, List<String> keptFolders, List<String> notRemoved,
                         List<LeftBehind> leftBehind, List<SplitBrief> splits, List<SplitMerged> splitsMerged,
                         List<Match> matches, List<String> notes, String finishedAt) {
    }

    /** A split species merged away by a later step: its photos still to sort moved to {@code into} with the merge. */
    public record SplitMerged(Ref split, Ref into, int photos) {
    }

    /** A photo given up after {@code missing} and Retry: it couldn't be found in its old or new folder. */
    public record LeftBehind(long id, String fileName, String oldPath) {
    }

    /** A split species with photos still to sort. */
    public record SplitBrief(String id, String name, String common, String family, int photos, String version,
                             List<Ref> targets) {
    }

    /** One species a split's photos can be sorted into. */
    public record SplitTarget(String id, String name, String common, String family, Count species) {
    }

    /**
     * A split species with its targets (those on the list), the species it was split into that are no longer on the list,
     * and its photos still to sort ({@code left} of {@code total} live photos).
     */
    public record Split(SplitBrief taxon, List<SplitTarget> targets, List<Ref> missingTargets, int left, int total,
                        List<Photo> photos) {
    }

    public record MatchCustom(String id, String name, String common, int photos) {
    }

    public record MatchOfficial(String id, String name, String common, String family) {
    }

    /** A custom species that now matches an official species, offered at {@code offeredVersion}. */
    public record Match(MatchCustom custom, MatchOfficial official, String offeredVersion) {
    }

    /**
     * {@code /api/status}'s {@code taxonomyUpdate}: kept in memory; {@code dismissed} Home's "The taxonomy was updated"
     * card was dismissed for the update done.
     */
    public record Status(String state, String kind, boolean later, int done, int total, int step, int steps,
                         boolean continuing, String waitingFor, Notice notice, boolean dismissed) {
    }
}
