package com.pitpass.imports.alkamel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A stand-in for the Al Kamel site: serves the recorded directory listings
 * under {@code fixtures/alkamel/} at the paths they were captured from
 * ({@code manifest.json} maps file → site-relative path), plus any extra
 * bodies a test registers. Records every request so tests can count them.
 */
final class FixtureSite implements AutoCloseable {

    static final String FIXTURES = "fixtures/alkamel/";

    private final HttpServer server;
    private final Map<String, byte[]> bodies = new HashMap<>();
    final List<String> requests = new CopyOnWriteArrayList<>();
    final List<String> userAgents = new CopyOnWriteArrayList<>();

    FixtureSite() throws IOException {
        Map<String, String> manifest = new ObjectMapper().readValue(
                resource("manifest.json"), new com.fasterxml.jackson.core.type.TypeReference<>() {
                });
        for (Map.Entry<String, String> e : manifest.entrySet()) {
            bodies.put(e.getValue(), resource(e.getKey()));
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/Results/", exchange -> {
            String path = exchange.getRequestURI().getRawPath().substring("/Results/".length());
            requests.add(path);
            userAgents.add(exchange.getRequestHeaders().getFirst("User-Agent"));
            byte[] body = bodies.get(path);
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    /** The base URL an {@link AlKamelClient} should be pointed at. */
    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/Results/";
    }

    /** Serve {@code body} at a site-relative path. */
    void put(String path, byte[] body) {
        bodies.put(path, body);
    }

    void put(String path, String body) {
        put(path, body.getBytes(StandardCharsets.UTF_8));
    }

    /** A client with no throttle and a long cache, unless a test says otherwise. */
    AlKamelClient client() {
        return client(0, 3600, 50L * 1024 * 1024);
    }

    AlKamelClient client(long delayMs, long cacheSeconds, long maxBytes) {
        return new AlKamelClient(baseUrl(), delayMs, cacheSeconds, maxBytes);
    }

    static byte[] resource(String name) throws IOException {
        try (InputStream in = FixtureSite.class.getClassLoader().getResourceAsStream(FIXTURES + name)) {
            if (in == null) {
                throw new IOException("Missing fixture " + FIXTURES + name);
            }
            return in.readAllBytes();
        }
    }

    static String text(String name) throws IOException {
        return new String(resource(name), StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
