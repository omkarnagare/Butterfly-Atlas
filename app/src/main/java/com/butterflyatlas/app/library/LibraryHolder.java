package com.butterflyatlas.app.library;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.db.Database;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** The open library, if any. Services ask it for the database (or the whole library) on every call. */
public final class LibraryHolder implements Supplier<Database> {

    private final AtomicReference<Library> current = new AtomicReference<>();
    private final List<Consumer<Library>> opened = new CopyOnWriteArrayList<>();

    /** Runs {@code listener} each time a library is opened (at launch or by first-run setup). Keep it quick. */
    public void whenOpened(Consumer<Library> listener) {
        opened.add(listener);
    }

    public Optional<Library> current() {
        return Optional.ofNullable(current.get());
    }

    public void set(Library library) {
        if (!current.compareAndSet(null, library)) {
            throw new AtlasException(AtlasException.Kind.ALREADY_CONFIGURED, "Your library is already set up.");
        }
        opened.forEach(listener -> listener.accept(library));
    }

    /** The open library's database; fails with SETUP_REQUIRED until a folder is chosen. */
    @Override
    public Database get() {
        return library().database();
    }

    /** The open library (data folder and database); fails with SETUP_REQUIRED until a folder is chosen. */
    public Library library() {
        Library library = current.get();
        if (library == null) {
            throw AtlasException.noLibrary();
        }
        return library;
    }
}
