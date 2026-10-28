package com.butterflyatlas.app;

import com.butterflyatlas.app.web.Api;
import com.butterflyatlas.app.web.AtlasServices;
import com.butterflyatlas.app.web.Session;
import io.javalin.Javalin;
import io.javalin.http.HandlerType;
import io.javalin.http.staticfiles.Location;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.ServerConnector;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.channels.ServerSocketChannel;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The local HTTP server behind the Butterfly Atlas window. It only ever listens on the loopback
 * address, so nothing outside this computer can reach it.
 */
public final class AtlasServer implements AutoCloseable {

    public static final String HOST = "127.0.0.1";

    private final Javalin javalin;
    private final Runnable closeApi;
    private final List<String> routes;

    private AtlasServer(Javalin javalin, Runnable closeApi, List<String> routes) {
        this.javalin = javalin;
        this.closeApi = closeApi;
        this.routes = routes;
    }

    /**
     * Starts the server with the given services. Pass port {@code 0} to let the operating system
     * pick a free port.
     */
    public static AtlasServer start(int port, AtlasServices services) {
        return start(port, services, null);
    }

    /**
     * As {@link #start(int, AtlasServices)}, with the per-launch secret: every request but the launch exchange needs the
     * session's cookie (contract §2). Without one (the app's own tests) every request is answered as before.
     */
    public static AtlasServer start(int port, AtlasServices services, Session session) {
        List<String> routes = new CopyOnWriteArrayList<>();
        Javalin javalin = Javalin.create(config -> {
            config.showJavalinBanner = false;
            config.events(events -> events.handlerAdded(info -> {
                if (Set.of(HandlerType.GET, HandlerType.POST, HandlerType.PUT, HandlerType.PATCH, HandlerType.DELETE)
                        .contains(info.getHttpMethod())) {
                    routes.add(info.getHttpMethod() + " " + info.getPath());
                }
            }));
            // The UI bundle is on the classpath under /ui once the ui module has been built.
            if (AtlasServer.class.getResource("/ui/index.html") != null) {
                config.staticFiles.add(staticFiles -> {
                    staticFiles.hostedPath = "/";
                    staticFiles.directory = "/ui";
                    staticFiles.location = Location.CLASSPATH;
                });
            }
        });
        Runnable closeApi = Api.install(javalin, services, session);
        try {
            javalin.start(HOST, port);
        } catch (RuntimeException e) {
            javalin.stop();
            closeApi.run();
            throw e;
        }
        return new AtlasServer(javalin, closeApi, routes.stream().sorted().toList());
    }

    /**
     * The routes registered ({@code "GET /api/exports/{id}"}), sorted: the packaging smoke test's list is checked against
     * it, so a route added later cannot be left out of the smoke test.
     */
    public List<String> routes() {
        return routes;
    }

    public int port() {
        return javalin.port();
    }

    public URI baseUri() {
        return URI.create("http://" + HOST + ":" + port() + "/");
    }

    /**
     * Whether every network connector Jetty has open is bound to a loopback address; it exists to
     * verify the privacy guarantee that nothing outside this computer can connect.
     */
    public boolean isLoopbackOnly() {
        Connector[] connectors = javalin.jettyServer().server().getConnectors();
        List<ServerConnector> networkConnectors = Arrays.stream(connectors)
                .filter(ServerConnector.class::isInstance)
                .map(ServerConnector.class::cast)
                .toList();
        if (networkConnectors.isEmpty()) {
            throw new IllegalStateException("Server has no network connector");
        }
        return networkConnectors.stream().allMatch(AtlasServer::isBoundToLoopback);
    }

    private static boolean isBoundToLoopback(ServerConnector connector) {
        try {
            if (connector.getTransport() instanceof ServerSocketChannel channel
                    && channel.getLocalAddress() instanceof InetSocketAddress address) {
                return address.getAddress().isLoopbackAddress();
            }
            return false;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Stops serving, then waits for the status's read under way, if any (none starts after). */
    @Override
    public void close() {
        try {
            javalin.stop();
        } finally {
            closeApi.run();
        }
    }
}
