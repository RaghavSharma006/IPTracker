package org.example.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import org.example.db.Database;
import org.example.db.Database.TokenRecord;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

public class TokenHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void handleCreateToken(HttpExchange exchange) {
        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            ObjectNodeWrapper req = MAPPER.readValue(body, ObjectNodeWrapper.class);
            String email = req.email();

            if (email == null || email.isBlank()) {
                sendError(exchange, 400, "email is required");
                return;
            }

            TokenRecord record = Database.createToken(email);

            String protocol = System.getenv().getOrDefault("PROTOCOL", "http");
            String host = System.getenv().getOrDefault("RAILWAY_PUBLIC_DOMAIN", "localhost:8080");
            String link = protocol + "://" + host + "/t/" + record.token();

            var resp = MAPPER.createObjectNode();
            resp.put("token", record.token());
            resp.put("email", record.email());
            resp.put("link", link);
            resp.put("created_at", Instant.now().toString());

            sendJson(exchange, 200, resp);
        } catch (Exception e) {
            try {
                sendError(exchange, 500, e.getMessage());
            } catch (Exception ignored) {}
        } finally {
            exchange.close();
        }
    }

    public static void handleTrigger(HttpExchange exchange, org.example.email.EmailSender emailSender) {
        try {
            String path = exchange.getRequestURI().getPath();
            String token = path.startsWith("/t/") ? path.substring(3) : "unknown";

            TokenRecord record = Database.findByToken(token);

            if (record == null) {
                sendError(exchange, 404, "token not found");
                return;
            }

            InetAddress remote = exchange.getRemoteAddress().getAddress();
            String ip = remote.getHostAddress();
            String userAgent = exchange.getRequestHeaders().getFirst("User-Agent");
            Instant time = Instant.now();

            System.out.println("🚨 Canary token triggered: " + token + " from " + ip);

            // Fire email asynchronously
            new Thread(() -> emailSender.sendAlert(
                    record.email(), token, ip, userAgent, time
            )).start();

            // 204 No Content
            exchange.sendResponseHeaders(204, -1);
        } catch (Exception e) {
            try {
                sendError(exchange, 500, e.getMessage());
            } catch (Exception ignored) {}
        } finally {
            exchange.close();
        }
    }

    private record ObjectNodeWrapper(String email) {}

    private static void sendJson(HttpExchange exchange, int code, com.fasterxml.jackson.databind.node.ObjectNode node) throws IOException {
        byte[] resp = MAPPER.writeValueAsBytes(node);
        exchange.sendResponseHeaders(code, resp.length);
        try (var os = exchange.getResponseBody()) {
            os.write(resp);
        }
    }

    private static void sendError(HttpExchange exchange, int code, String msg) throws IOException {
        var node = MAPPER.createObjectNode();
        node.put("error", msg);
        byte[] resp = MAPPER.writeValueAsBytes(node);
        exchange.sendResponseHeaders(code, resp.length);
        try (var os = exchange.getResponseBody()) {
            os.write(resp);
        }
    }
}
