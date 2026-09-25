package org.example;

import com.sun.net.httpserver.HttpServer;
import org.example.db.Database;
import org.example.email.EmailSender;
import org.example.handler.TokenHandler;
import org.example.handler.UiHandler;

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

        // Link generator UI
        server.createContext("/", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                UiHandler.serve(exchange);
            } else {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
            }
        });

        // Create a tracking token
        server.createContext("/api/tokens", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                TokenHandler.handleCreateToken(exchange);
            } else {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
            }
        });

        // View events for a token (owner only via ?key=<token>)
        server.createContext("/api/tokens/", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                TokenHandler.handleGetEvents(exchange);
            } else {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
            }
        });

        // Redirect + track endpoint
        server.createContext("/r/", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                TokenHandler.handleRedirect(exchange, emailSender);
            } else {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
            }
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
        System.out.println("  GET  /             - Link generator UI");
        System.out.println("  POST /api/tokens   - Create a tracking token");
        System.out.println("  GET  /r/{token}    - Track event + redirect");
        System.out.println("  GET  /api/tokens/{token}?key={token} - View events");
        System.out.println("  GET  /health        - Health check");
        System.out.println();
    }
}
