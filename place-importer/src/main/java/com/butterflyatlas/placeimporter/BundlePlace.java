package com.butterflyatlas.placeimporter;

import java.util.List;
import java.util.Map;

/** One entry of the bundle JSON's {@code places} (contract §2). {@code parent} is null for countries. */
public record BundlePlace(String id, String parent, String level, String name, List<String> aliases,
                          Map<String, String> codes) {
}
