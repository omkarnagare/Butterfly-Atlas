package com.butterflyatlas.importer.inat;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Answers requests from canned routes (first route whose text appears in the URL wins). */
public final class FakeTransport implements Transport {

    private final List<Map.Entry<String, Function<URI, String>>> routes = new ArrayList<>();
    private final List<String> requests = new ArrayList<>();

    public FakeTransport respond(String urlContains, String body) {
        return route(urlContains, uri -> body);
    }

    public FakeTransport route(String urlContains, Function<URI, String> handler) {
        routes.add(Map.entry(urlContains, handler));
        return this;
    }

    public List<String> requests() {
        return requests;
    }

    @Override
    public String get(URI uri) {
        String url = uri.toString();
        requests.add(url);
        return routes.stream()
                .filter(route -> url.contains(route.getKey()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Unexpected request: " + url))
                .getValue()
                .apply(uri);
    }
}
