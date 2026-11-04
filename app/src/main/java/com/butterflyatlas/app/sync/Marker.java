package com.butterflyatlas.app.sync;

import java.time.Instant;

/**
 * The library's device marker (one-device design §3): which device uses the library ({@code device}, its ID, and its
 * {@code name} for messages, null when unknown), whether it is {@code open} or {@code closed}, since when it has it open,
 * when it was last seen ({@code open}) or closed it ({@code closed}), and the number of the snapshot it last claimed or
 * published.
 */
public record Marker(String device, String name, String state, Instant since, Instant seen, long generation) {

    public static final String OPEN = "open";
    public static final String CLOSED = "closed";

    public boolean open() {
        return OPEN.equals(state);
    }

    /** Whether the marker names {@code deviceId}. */
    public boolean names(String deviceId) {
        return device.equals(deviceId);
    }

    /** This marker as {@code closed} at {@code at}. */
    public Marker closedAt(Instant at) {
        return new Marker(device, name, CLOSED, since, at, generation);
    }
}
