package com.butterflyatlas.placeimporter;

import org.locationtech.jts.algorithm.locate.IndexedPointInAreaLocator;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Location;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.index.strtree.STRtree;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Finds which of many polygons contain a point; when several do, the one added first wins. */
public final class PolygonIndex<T> {

    private record Item<T>(int order, Geometry geometry, T value, IndexedPointInAreaLocator locator) {
    }

    private final STRtree tree = new STRtree();
    private final List<Item<T>> items = new ArrayList<>();

    public void add(Geometry geometry, T value) {
        Item<T> item = new Item<>(items.size(), geometry, value, new IndexedPointInAreaLocator(geometry));
        items.add(item);
        tree.insert(geometry.getEnvelopeInternal(), item);
    }

    /** The first-added polygon containing the point (inside or on its edge), else null. */
    public T first(Point point) {
        return containing(point).stream().findFirst().orElse(null);
    }

    /** Every polygon containing the point, in the order they were added. */
    @SuppressWarnings("unchecked")
    public List<T> containing(Point point) {
        List<Item<T>> hits = new ArrayList<>();
        for (Object found : tree.query(point.getEnvelopeInternal())) {
            Item<T> item = (Item<T>) found;
            if (item.locator().locate(point.getCoordinate()) != Location.EXTERIOR) {
                hits.add(item);
            }
        }
        hits.sort(Comparator.comparingInt(Item::order));
        return hits.stream().map(Item::value).toList();
    }

    /** The polygon nearest to the point (the first-added on a tie), else null when the index is empty. */
    public T nearest(Point point) {
        T best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Item<T> item : items) {
            double distance = item.geometry().distance(point);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = item.value();
            }
        }
        return best;
    }

    public int size() {
        return items.size();
    }
}
