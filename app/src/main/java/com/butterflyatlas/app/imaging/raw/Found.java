package com.butterflyatlas.app.imaging.raw;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What a reader collects while it walks a RAW file; {@link #contents} then chooses the preview. */
final class Found {

    /** Candidate JPEGs a reader may list; real files have two to five. */
    static final int MAX_CANDIDATES = 64;

    String family;
    int orientation = 1;
    RawContents.Size sensor;
    final List<RawContents.Slice> cmt = new ArrayList<>();
    RawContents.Slice embeddedJpeg;
    /** offset → length, in the order found (the first of two equally large previews wins). */
    private final Map<Long, Long> candidates = new LinkedHashMap<>();

    void candidate(long offset, long length) {
        if (candidates.size() < MAX_CANDIDATES) {
            candidates.putIfAbsent(offset, length);
        }
    }

    void orientation(long value) {
        if (value >= 1 && value <= 8) {
            orientation = (int) value;
        }
    }

    /** Keeps the largest sensor-sized image seen. */
    void sensor(long width, long height) {
        if (width > 0 && height > 0 && width <= 0xFFFF && height <= 0xFFFF
                && (sensor == null || width * height > (long) sensor.width() * sensor.height())) {
            sensor = new RawContents.Size((int) width, (int) height);
        }
    }

    /**
     * The contents, with the largest usable candidate as the preview: a baseline, extended or progressive JPEG inside
     * the file, at most {@link RawPreviews#MAX_PREVIEW_BYTES}, at least {@link RawPreviews#MIN_LONG_SIDE} px on its
     * long side.
     */
    RawContents contents(RawBytes in) throws IOException {
        RawPreview best = null;
        for (Map.Entry<Long, Long> candidate : candidates.entrySet()) {
            long offset = candidate.getKey();
            long length = candidate.getValue();
            if (length > RawPreviews.MAX_PREVIEW_BYTES || !in.inside(offset, length)) {
                continue;
            }
            JpegCheck.Size size = JpegCheck.size(in, offset, length);
            if (size == null || Math.max(size.width(), size.height()) < RawPreviews.MIN_LONG_SIDE) {
                continue;
            }
            if (best == null || size.pixels() > (long) best.storedWidth() * best.storedHeight()) {
                best = new RawPreview(offset, length, size.width(), size.height(), orientation);
            }
        }
        return new RawContents(family, best, sensor, List.copyOf(cmt), embeddedJpeg);
    }
}
