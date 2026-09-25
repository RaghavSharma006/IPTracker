package org.example.redirect;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;

public class RedirectServer {

    private static final int PORT = Integer.parseInt(
            System.getenv().getOrDefault("PORT", "8080")
    );

    public static void main(String[] args) throws Exception {
        // Redirect service does NOT use local SQLite — token lookups go through tracking service API
        // Validate required config
        String trackingUrl = System.getenv().getOrDefault("TRACKING_SERVICE_URL", "");
        String internalSecret = System.getenv().getOrDefault("INTERNAL_API_SECRET", "");

        if (trackingUrl.isBlank()) {
            System.err.println("[CONFIG] TRACKING_SERVICE_URL is not set. Event forwarding will fail.");
        }
        if (internalSecret.isBlank()) {
            System.err.println("[CONFIG] INTERNAL_API_SECRET is not set. Event forwarding will fail.");
        }

        HttpServer server = HttpServer.create(
                new InetSocketAddress("0.0.0.0", PORT),
                0
        );

        // Public tracking redirect endpoint
        server.createContext("/r/", exchange -> RedirectHandler.handle(exchange));

        // Health check
        server.createContext("/health", exchange -> {
            byte[] body = "{\"status\":\"ok\"}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) { os.write(body); }
            exchange.close();
        });

        server.start();

        System.out.println("=================================");
        System.out.println("   REDIRECT SERVICE STARTED");
        System.out.println("=================================");
        System.out.println("Port:  " + PORT);
        System.out.println("Tracking Service URL: " + (trackingUrl.isBlank() ? "(NOT SET)" : trackingUrl));
        System.out.println("Endpoints:");
        System.out.println("  GET  /r/{token}   - Track + redirect");
        System.out.println("  GET  /health     - Health check");
        System.out.println("=================================");
    }
}
