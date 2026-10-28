package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.AtlasException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** File moves done inside a transaction, so they can be put back if it does not commit. */
final class FileMoves {

    private static final Logger LOG = LoggerFactory.getLogger(FileMoves.class);

    private final List<Path[]> done = new ArrayList<>();
    private final List<Path> notPutBack = new ArrayList<>();

    void move(Path from, Path to, String fileName) {
        try {
            PhotoFiles.move(from, to);
        } catch (NoSuchFileException e) {
            throw AtlasException.notFound("The file of " + fileName + " can no longer be found.");
        } catch (FileSystemException e) { // includes AccessDeniedException
            throw locked(fileName);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not move " + from + " to " + to, e);
        }
        done.add(new Path[] {from, to});
    }

    private static AtlasException locked(String fileName) {
        return AtlasException.invalid(fileName + " is open in another program. Close it and try again.");
    }

    /** The {@code from} of each move {@link #undo} could not put back. */
    List<Path> notPutBack() {
        return notPutBack;
    }

    void undo() {
        for (int i = done.size() - 1; i >= 0; i--) {
            Path[] move = done.get(i);
            try {
                PhotoFiles.move(move[1], move[0]);
            } catch (IOException | RuntimeException e) {
                LOG.error("Could not put {} back at {}", move[1], move[0], e);
                notPutBack.add(move[0]);
            }
        }
        done.clear();
    }
}
