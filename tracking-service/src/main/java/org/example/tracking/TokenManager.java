package org.example.tracking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import org.example.db.Database;
import org.example.db.Database.TokenRecord;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.regex.Pattern;

public class TokenManager {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE_URL = System.getenv().getOrDefault("BASE_URL", "http://localhost:8080");

    private static final Pattern URL_PATTERN = Pattern.compile("^https?://.+");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@]+@[^@]+\\.[^@]+$");

    public static void handleCreate(HttpExchange exchange) {
        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode req = MAPPER.readTree(body);

            String destinationUrl = req.path("destination_url").asText("").trim();
            String notificationEmail = req.path("notification_email").asText("").trim();

            if (destinationUrl.isEmpty() || notificationEmail.isEmpty()) {
                sendError(exchange, 400, "destination_url and notification_email are required");
                return;
            }

            if (!URL_PATTERN.matcher(destinationUrl).matches()) {
                sendError(exchange, 400, "destination_url must be a valid http:// or https:// URL");
                return;
            }

            try {
                URI.create(destinationUrl);
            } catch (Exception e) {
                sendError(exchange, 400, "destination_url is not a valid URL");
                return;
            }

            if (!EMAIL_PATTERN.matcher(notificationEmail).matches()) {
                sendError(exchange, 400, "notification_email is not valid");
                return;
            }

            TokenRecord record = Database.createToken(destinationUrl, notificationEmail);

            ObjectNode resp = MAPPER.createObjectNode();
            resp.put("token", record.token());
            resp.put("url", BASE_URL + "/r/" + record.token());
            resp.put("destination_url", record.destinationUrl());
            resp.put("notification_email", record.notificationEmail());
            resp.put("created_at", record.createdAt());

            sendJson(exchange, 200, resp);
        } catch (Exception e) {
            try { sendError(exchange, 500, e.getMessage()); } catch (Exception ignored) {}
        } finally {
            exchange.close();
        }
    }

    public static void handleGetEvents(HttpExchange exchange) {
        try {
            String path = exchange.getRequestURI().getPath();
            String token = path.startsWith("/api/tokens/") ? path.substring("/api/tokens/".length()) : "";

            String query = exchange.getRequestURI().getQuery();
            String key = null;
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] kv = param.split("=", 2);
                    if (kv.length == 2 && "key".equals(kv[0])) {
                        key = java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                    }
                }
            }

            TokenRecord record = Database.findByToken(token);
            if (record == null) {
                sendError(exchange, 404, "Token not found");
                return;
            }

            // Authorization: only the token owner (who knows the token) can view
            if (key == null || !key.equals(token)) {
                sendError(exchange, 403, "Unauthorized — provide ?key=<token> to view");
                return;
            }

            // Return token info + events
            ObjectNode resp = MAPPER.createObjectNode();
            resp.put("token", record.token());
            resp.put("destination_url", record.destinationUrl());
            resp.put("notification_email", record.notificationEmail());
            resp.put("created_at", record.createdAt());

            ArrayNode eventsArray = MAPPER.createArrayNode();
            for (var event : Database.getEventsByToken(token)) {
                ObjectNode e = MAPPER.createObjectNode();
                e.put("timestamp", event.timestamp());
                e.put("ip", event.ip());
                e.put("ip_version", event.ipVersion());
                e.put("country", event.country());
                e.put("region", event.region());
                e.put("city", event.city());
                e.put("latitude", event.latitude() != null ? event.latitude() : 0.0);
                e.put("longitude", event.longitude() != null ? event.longitude() : 0.0);
                e.put("timezone", event.timezone());
                e.put("asn", event.asn());
                e.put("organization", event.organization());
                e.put("isp", event.isp());
                e.put("user_agent", event.userAgent());
                e.put("referer", event.referer());
                eventsArray.add(e);
            }
            resp.set("events", eventsArray);

            sendJson(exchange, 200, resp);
        } catch (Exception e) {
            try { sendError(exchange, 500, e.getMessage()); } catch (Exception ignored) {}
        } finally {
            exchange.close();
        }
    }

    private static void sendJson(HttpExchange exchange, int code, ObjectNode node) throws IOException {
        byte[] resp = MAPPER.writeValueAsBytes(node);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, resp.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
    }

    private static void sendError(HttpExchange exchange, int code, String msg) throws IOException {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("error", msg);
        byte[] resp = MAPPER.writeValueAsBytes(node);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, resp.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
    }
}
