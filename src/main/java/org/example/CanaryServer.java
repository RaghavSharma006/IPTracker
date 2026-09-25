package org.example;

import com.sun.net.httpserver.HttpServer;
import org.example.db.Database;
import org.example.email.EmailSender;
import org.example.handler.TokenHandler;

import java.net.InetSocketAddress;

public class CanaryServer {

    private static final int PORT = Integer.parseInt(
            System.getenv().getOrDefault("PORT", "8080")
    );

    public static void main(String[] args) throws Exception {
        Database.init();

        EmailSender emailSender = new EmailSender();

        HttpServer server = HttpServer.create(
                new InetSocketAddress("0.0.0.0", PORT),
                0
        );

        // Create a token (POST /api/tokens)
        server.createContext("/api/tokens", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                TokenHandler.handleCreateToken(exchange);
            } else {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
            }
        });

        // Trigger endpoint (GET /t/{token})
        server.createContext("/t/", exchange -> {
            TokenHandler.handleTrigger(exchange, emailSender);
        });

        // Health check
        server.createContext("/health", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        server.start();

        System.out.println("=================================");
        System.out.println("      CANARY SERVER STARTED");
        System.out.println("=================================");
        System.out.println("Port:  " + PORT);
        System.out.println();
        System.out.println("Endpoints:");
        System.out.println("  POST /api/tokens  - Create a canary token");
        System.out.println("  GET  /t/{token}   - Trigger endpoint");
        System.out.println("  GET  /health     - Health check");
        System.out.println();
    }

}
