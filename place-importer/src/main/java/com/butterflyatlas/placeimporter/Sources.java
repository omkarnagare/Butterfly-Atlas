package com.butterflyatlas.placeimporter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/**
 * The pinned boundary sources: each by a URL at a fixed commit and the SHA-256 of its bytes. Downloads are kept in
 * {@code place-importer/.cache/} (git-ignored) and checked against the SHA-256 every run.
 */
public final class Sources {

    public record Source(String file, URI uri, String sha256) {
    }

    private static final String NATURAL_EARTH =
            "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/f1890d9f152c896d250a77557a5751a93d494776/geojson/";
    private static final String GEOBOUNDARIES =
            "https://github.com/wmgeolab/geoBoundaries/raw/9469f09592ced973a3448cf66b6100b741b64c0d/releaseData/gbOpen/IND/";

    /** Natural Earth v5.1.2, 1:10m admin-0 countries from India's point of view. */
    public static final Source NE_COUNTRIES = new Source("ne_10m_admin_0_countries_ind.geojson",
            URI.create(NATURAL_EARTH + "ne_10m_admin_0_countries_ind.geojson"),
            "3e67a5a6ddeff3247ca8400cd2514fd4a5ac6e5432823b25d610a7028597d45f");
    /** Natural Earth v5.1.2, 1:10m admin-1 states and provinces. */
    public static final Source NE_STATES = new Source("ne_10m_admin_1_states_provinces.geojson",
            URI.create(NATURAL_EARTH + "ne_10m_admin_1_states_provinces.geojson"),
            "22d0e3ad85eb3e27f17cabf8ba2d50e554fbc27a87796ff891d958185da62fb5");
    /** geoBoundaries gbOpen India ADM1 (states and union territories), release of 2023-12-12. */
    public static final Source GB_STATES = new Source("geoBoundaries-IND-ADM1.geojson",
            URI.create(GEOBOUNDARIES + "ADM1/geoBoundaries-IND-ADM1.geojson"),
            "47aa0acb6f69868daee49143276f3ce323f18905ba7d38d0a86816b405028736");
    /** geoBoundaries gbOpen India ADM2 (districts), release of 2023-12-12. */
    public static final Source GB_DISTRICTS = new Source("geoBoundaries-IND-ADM2.geojson",
            URI.create(GEOBOUNDARIES + "ADM2/geoBoundaries-IND-ADM2.geojson"),
            "8bef6929fd65432e7dc775e7c44473e84efff064ebddbfd6b834e6291546db40");

    public static final List<Source> ALL = List.of(NE_COUNTRIES, NE_STATES, GB_STATES, GB_DISTRICTS);

    /** Saves the resource at {@code uri} to {@code target}. */
    @FunctionalInterface
    public interface Fetcher {
        void fetch(URI uri, Path target) throws IOException;
    }

    private Sources() {
    }

    /** The source's file in {@code cache}, downloaded first unless a copy with the pinned SHA-256 is already there. */
    public static Path ensure(Source source, Path cache, Fetcher fetcher) throws IOException {
        Path file = cache.resolve(source.file());
        if (Files.exists(file) && sha256(file).equals(source.sha256())) {
            return file;
        }
        Files.createDirectories(cache);
        Path part = cache.resolve(source.file() + ".part");
        try {
            fetcher.fetch(source.uri(), part);
            String sha = sha256(part);
            if (!sha.equals(source.sha256())) {
                throw new IOException(source.uri() + " has SHA-256 " + sha + ", expected " + source.sha256());
            }
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(part);
        }
        return file;
    }

    /** The file's SHA-256 (hex), read as a stream: the sources are tens of megabytes. */
    public static String sha256(Path file) throws IOException {
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), MessageDigest.getInstance("SHA-256"))) {
            in.transferTo(OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(((DigestInputStream) in).getMessageDigest().digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }

    /** Plain HTTPS GET, following redirects (GitHub serves the geoBoundaries files from its LFS host). */
    public static Fetcher http() {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        return (uri, target) -> {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(10))
                    .header("User-Agent", "ButterflyAtlas-place-importer/0.1").GET().build();
            try {
                HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(target));
                if (response.statusCode() != 200) {
                    throw new IOException(uri + " answered HTTP " + response.statusCode());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while downloading " + uri, e);
            }
        };
    }

    /** Reads the four sources from a directory holding them under their file names. */
    public static AtlasBuilder.Sources read(Path directory) throws IOException {
        return new AtlasBuilder.Sources(
                GeoJson.read(directory.resolve(NE_COUNTRIES.file())),
                GeoJson.read(directory.resolve(NE_STATES.file())),
                GeoJson.read(directory.resolve(GB_STATES.file())),
                GeoJson.read(directory.resolve(GB_DISTRICTS.file())));
    }

    /** NOTICE.txt: the sources, their licences and credits, and what was changed. */
    public static String notice(String version, String lgdFile, String lgdSha256) {
        String lgd = lgdFile == null
                ? "4. Local Government Directory (LGD): not used for this build (no --lgd list was given).\n"
                : "4. Local Government Directory (LGD), Ministry of Panchayati Raj, Government of India\n"
                + "   (https://lgdirectory.gov.in): district names and LGD codes, from the list " + lgdFile + "\n"
                + "   (SHA-256 " + lgdSha256 + "). Indian district names follow the LGD spelling where a\n"
                + "   boundary matches an LGD district; the boundary's own spelling is kept as an alias.\n";
        return "Place names and boundaries bundled with Butterfly Atlas (places-" + version + ".json and .bin)\n"
                + "\n"
                + "Built by the developer tool place-importer from these sources:\n"
                + "\n"
                + "1. geoBoundaries gbOpen, India ADM2 (districts), release of 2023-12-12\n"
                + "   " + GB_DISTRICTS.uri() + "\n"
                + "   Runfola, D. et al. (2020) geoBoundaries: A global database of political administrative\n"
                + "   boundaries. PLoS ONE 15(4): e0231866. https://www.geoboundaries.org\n"
                + "   Upstream: Pathways Data Pvt. Ltd. (geonode.pathwaysdata.com), lgdirectory.gov.in.\n"
                + "   Licence: Open Data Commons Open Database License (ODbL) 1.0,\n"
                + "   https://opendatacommons.org/licenses/odbl/1-0/\n"
                + "   The boundaries and the place list in these files are a Derived Database of it and are made\n"
                + "   available under the ODbL 1.0.\n"
                + "\n"
                + "2. geoBoundaries gbOpen, India ADM1 (states and union territories), release of 2023-12-12\n"
                + "   " + GB_STATES.uri() + "\n"
                + "   Upstream: DataMeet India community (https://github.com/datameet/maps), Election\n"
                + "   Commission of India. Licence: Creative Commons Attribution 2.5 India (CC BY 2.5 IN),\n"
                + "   https://creativecommons.org/licenses/by/2.5/in/\n"
                + "\n"
                + "3. Natural Earth v5.1.2, 1:10m Admin 0 countries (India's point of view) and Admin 1 states and\n"
                + "   provinces. Made with Natural Earth. Free vector and raster map data @ naturalearthdata.com.\n"
                + "   Public domain. https://www.naturalearthdata.com\n"
                + "\n"
                + lgd
                + "\n"
                + "Changes: borders noded into one coverage on a 1e-5 degree grid and simplified (about 100 m in\n"
                + "India, about 1 km elsewhere); disputed areas follow India's official view (Natural Earth's\n"
                + "India point of view); Indian states and districts clipped to India's outline; areas of India\n"
                + "that geoBoundaries gives no district are areas of their state.\n";
    }
}
