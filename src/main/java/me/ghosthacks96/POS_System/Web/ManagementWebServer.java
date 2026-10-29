package me.ghosthacks96.POS_System.Web;

import io.javalin.Javalin;
import me.ghosthacks96.POS_System.utilities.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

public final class ManagementWebServer implements AutoCloseable {
    private final String apiBaseUrl;
    private final String apiOrigin;
    private final Logger logger;
    private final String indexHtml;
    private final String appJavaScript;
    private final String managementCss;
    private final String suppliedCss;
    private Javalin app;

    public ManagementWebServer(String apiBaseUrl, Logger logger) {
        this.apiBaseUrl = normalizeApiBaseUrl(apiBaseUrl);
        this.apiOrigin = originOf(this.apiBaseUrl);
        this.logger = Objects.requireNonNull(logger, "logger");
        indexHtml = readResource("/web/index.html");
        appJavaScript = readResource("/web/app.js");
        managementCss = readResource("/web/management.css");
        suppliedCss = readResource("/style.css");
    }

    public synchronized void start(String host, int port, int apiPort) {
        if (app != null) {
            throw new IllegalStateException("Management web server is already running");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Management web host cannot be blank");
        }
        if (port < 1 || port > 65_535 || port == apiPort) {
            throw new IllegalArgumentException("Management web port must be valid and separate from the API port");
        }

        app = Javalin.create();
        app.before(ctx -> {
            ctx.header("X-Content-Type-Options", "nosniff");
            ctx.header("Referrer-Policy", "same-origin");
            ctx.header("X-Frame-Options", "DENY");
            ctx.header(
                    "Content-Security-Policy",
                    "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self' "
                            + apiOrigin + "; img-src 'self' data:; object-src 'none'; base-uri 'self'; "
                            + "frame-ancestors 'none'"
            );
        });
        app.get("/", ctx -> ctx.contentType("text/html; charset=utf-8").result(indexHtml));
        app.get("/index.html", ctx -> ctx.contentType("text/html; charset=utf-8").result(indexHtml));
        app.get("/app.js", ctx -> ctx.contentType("text/javascript; charset=utf-8").result(appJavaScript));
        app.get("/management.css", ctx ->
                ctx.contentType("text/css; charset=utf-8").result(managementCss));
        app.get("/style.css", ctx -> ctx.contentType("text/css; charset=utf-8").result(suppliedCss));
        app.get("/ui-config.json", ctx -> ctx.json(Map.of("apiBaseUrl", apiBaseUrl)));
        try {
            app.start(host, port);
            logger.info("Management web interface listening on " + host + ":" + port);
            if (!isLoopbackHost(host)) {
                logger.warning("Management web interface is exposed beyond localhost; terminate TLS at a trusted reverse proxy.");
            }
        } catch (RuntimeException exception) {
            app = null;
            throw exception;
        }
    }

    @Override
    public synchronized void close() {
        if (app != null) {
            app.stop();
            app = null;
            logger.info("Management web interface stopped");
        }
    }

    private static String normalizeApiBaseUrl(String apiBaseUrl) {
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            throw new IllegalArgumentException("Management UI requires a browser-accessible API base URL");
        }
        String normalized = apiBaseUrl.trim().replaceAll("/+$", "");
        URI uri;
        try {
            uri = URI.create(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Management UI API base URL is invalid", exception);
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("Management UI API URL must be an HTTP(S) URL without credentials");
        }
        return normalized;
    }

    private static String originOf(String apiBaseUrl) {
        URI uri = URI.create(apiBaseUrl);
        return uri.getScheme() + "://" + uri.getRawAuthority();
    }

    private static String readResource(String path) {
        try (InputStream input = ManagementWebServer.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("Management web resource is missing: " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load management web resource: " + path, exception);
        }
    }

    private static boolean isLoopbackHost(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }
}
