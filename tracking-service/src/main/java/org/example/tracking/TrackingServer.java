package org.example.tracking;

import com.sun.net.httpserver.HttpServer;
import org.example.db.Database;

import java.net.InetSocketAddress;

public class TrackingServer {

    private static final int PORT = Integer.parseInt(
            System.getenv().getOrDefault("PORT", "8080")
    );

    public static void main(String[] args) throws Exception {
        // Validate SMTP_PORT
        String smtpPortStr = System.getenv().getOrDefault("SMTP_PORT", "587").trim();
        int smtpPort;
        try {
            smtpPort = Integer.parseInt(smtpPortStr);
        } catch (NumberFormatException e) {
            System.err.println("[CONFIG] SMTP_PORT='" + smtpPortStr + "' is invalid; expected an integer such as 587");
            System.err.println("[CONFIG] Using default 587");
            smtpPort = 587;
        }

        String smtpHost = System.getenv().getOrDefault("SMTP_HOST", "smtp.gmail.com");
        String smtpUser = System.getenv().getOrDefault("SMTP_USER", "");
        String smtpPass = System.getenv().getOrDefault("SMTP_PASS", "");
        String internalSecret = System.getenv().getOrDefault("INTERNAL_API_SECRET", "");

        if (smtpHost.isBlank()) {
            System.out.println("[CONFIG] SMTP_HOST not set — emails will be disabled");
        }
        if (smtpUser.isBlank()) {
            System.out.println("[CONFIG] SMTP_USER not set — emails will be disabled");
        }
        // ponytail: SMTP_PASS is never logged
        if (smtpPass.isBlank()) {
            System.out.println("[CONFIG] SMTP_PASS not set — emails will be disabled");
        }
        if (internalSecret.isBlank()) {
            System.err.println("[CONFIG] INTERNAL_API_SECRET not set — internal endpoint will reject all requests");
        }

        Database.init();
        EmailSender.init(smtpHost, smtpPort, smtpUser, smtpPass);

        HttpServer server = HttpServer.create(
                new InetSocketAddress("0.0.0.0", PORT),
                0
        );

        // UI at root
        server.createContext("/", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                UiHandler.serve(exchange);
            } else {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
            }
        });

        // Token creation
        server.createContext("/api/tokens", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                TokenManager.handleCreate(exchange);
            } else {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
            }
        });

        // Token lookup (protected)
        server.createContext("/api/tokens/", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                TokenManager.handleGetEvents(exchange);
            } else {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
            }
        });

        // Internal event reception (authenticated)
        server.createContext("/internal/events", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                EventReceiver.handle(exchange);
            } else {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
            }
        });

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
        System.out.println("  TRACKING SERVICE STARTED");
        System.out.println("=================================");
        System.out.println("Port:  " + PORT);
        System.out.println("SMTP:  " + smtpHost + ":" + smtpPort + " (user: " + smtpUser + ")");
        System.out.println("Endpoints:");
        System.out.println("  GET  /                  - Link generator UI");
        System.out.println("  POST /api/tokens       - Create a tracking token");
        System.out.println("  GET  /api/tokens/{tok}?key={tok} - View events (owner only)");
        System.out.println("  POST /internal/events  - Receive tracked events (authenticated)");
        System.out.println("  GET  /health           - Health check");
        System.out.println("=================================");
    }
}
