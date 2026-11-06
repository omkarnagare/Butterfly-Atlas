package com.butterflyatlas.app.testing;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CompletableFuture;

/** A program the app "started" that exits when the test says so. */
public final class FakeProcess extends Process {

    private final CompletableFuture<Process> exit = new CompletableFuture<>();
    private final long pid;
    private volatile int code;

    /** A program with no process ID ({@link #pid()} is unsupported, as for any {@link Process} the JDK did not start). */
    public FakeProcess() {
        this(-1);
    }

    /** A program with this process ID. */
    public FakeProcess(long pid) {
        this.pid = pid;
    }

    @Override
    public long pid() {
        if (pid < 0) {
            throw new UnsupportedOperationException("a fake process with no ID");
        }
        return pid;
    }

    /** The program ends with this exit code. */
    public void exits(int code) {
        this.code = code;
        exit.complete(this);
    }

    @Override
    public OutputStream getOutputStream() {
        return OutputStream.nullOutputStream();
    }

    @Override
    public InputStream getInputStream() {
        return InputStream.nullInputStream();
    }

    @Override
    public InputStream getErrorStream() {
        return InputStream.nullInputStream();
    }

    @Override
    public int waitFor() {
        return exit.join().exitValue();
    }

    @Override
    public int exitValue() {
        if (!exit.isDone()) {
            throw new IllegalThreadStateException();
        }
        return code;
    }

    @Override
    public void destroy() {
    }

    @Override
    public CompletableFuture<Process> onExit() {
        return exit;
    }
}
