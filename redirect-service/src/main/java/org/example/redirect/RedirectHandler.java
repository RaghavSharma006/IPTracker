package org.example.redirect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
// Redirect service no longer uses local SQLite — all token lookups go through tracking service API

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class RedirectHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TRACKING_URL = System.getenv().getOrDefault("TRACKING_SERVICE_URL", "");
    private static final String INTERNAL_SECRET = System.getenv().getOrDefault("INTERNAL_API_SECRET", "");

    // ponytail: in-memory rate limiter, 60 req/min per IP
    private static final ConcurrentHashMap<String, RequestCounter> rateLimiter = new ConcurrentHashMap<>();

    public static void handle(HttpExchange exchange) {
        try {
            // Rate limit
            String clientIp = getClientIp(exchange);
            if (!rateLimitOk(clientIp)) {
                sendError(exchange, 429, "Too many requests");
                return;
            }

            String path = exchange.getRequestURI().getPath();
            String token = path.startsWith("/r/") ? path.substring(3) : "";

            if (token.isEmpty()) {
                sendError(exchange, 404, "Token not found");
                return;
            }

            // Validate token + retrieve destination via tracking service API
            String destination = lookupDestination(token);
            if (destination == null) {
                sendError(exchange, 404, "Tracking link not found");
                return;
            }

            // Capture request metadata
            InetAddress remote = exchange.getRemoteAddress().getAddress();
            String ip = remote.getHostAddress();
            String ipVersion = remote instanceof Inet4Address ? "IPv4" :
                    remote instanceof Inet6Address ? "IPv6" : "Unknown";
            String userAgent = exchange.getRequestHeaders().getFirst("User-Agent");
            String referer = exchange.getRequestHeaders().getFirst("Referer");
            Instant timestamp = Instant.now();

            System.out.println("[TRACK] token=" + token + " ip=" + ip + " ua=" + (userAgent != null ? userAgent.substring(0, Math.min(50, userAgent.length())) : "null"));

            // Forward event to Service 2
            new Thread(() -> {
                try {
                    forwardEvent(token, ip, ipVersion, userAgent, referer, timestamp);
                } catch (Exception e) {
                    System.err.println("[FORWARD ERROR] Failed to forward event: " + e.getMessage());
                }
            }).start();

            // Redirect visitor to destination
            exchange.getResponseHeaders().add("Location", destination);
            exchange.sendResponseHeaders(302, -1);
        } catch (Exception e) {
            try { sendError(exchange, 500, "Internal error"); } catch (Exception ignored) {}
        } finally {
            exchange.close();
        }
    }

    private static String lookupDestination(String token) {
        if (TRACKING_URL.isBlank()) return null;
        try {
            java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(TRACKING_URL + "/api/tokens/" + java.net.URLEncoder.encode(token, StandardCharsets.UTF_8) + "?key=" + java.net.URLEncoder.encode(token, StandardCharsets.UTF_8)))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            java.net.http.HttpResponse<String> resp = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;
            JsonNode node = MAPPER.readTree(resp.body());
            return node.path("destination_url").asText(null);
        } catch (Exception e) {
            System.err.println("[LOOKUP] Failed to lookup token " + token + ": " + e.getMessage());
            return null;
        }
    }

    private static void forwardEvent(String token, String ip, String ipVersion,
                                     String userAgent, String referer, Instant timestamp) throws IOException, InterruptedException {
        if (TRACKING_URL.isBlank() || INTERNAL_SECRET.isBlank()) {
            System.err.println("[FORWARD] TRACKING_SERVICE_URL or INTERNAL_API_SECRET not configured");
            return;
        }

        ObjectNode body = MAPPER.createObjectNode();
        body.put("token", token);
        body.put("timestamp", timestamp.toString());
        body.put("ip", ip);
        body.put("ip_version", ipVersion);
        body.put("user_agent", userAgent != null ? userAgent : "");
        body.put("referer", referer != null ? referer : "");

        long ts = timestamp.getEpochSecond() / 60;
        String msg = token + "|" + ip + "|" + ts;
        String signature = hmacSha256(INTERNAL_SECRET, msg);

        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(TRACKING_URL + "/internal/events"))
                .header("Content-Type", "application/json")
                .header("X-Signature", signature)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        java.net.http.HttpResponse<String> resp = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            System.err.println("[FORWARD] Service 2 returned " + resp.statusCode() + ": " + resp.body());
        }
    }

    private static String hmacSha256(String secret, String data) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(raw);
        } catch (Exception e) {
            throw new RuntimeException("HMAC failed", e);
        }
    }

    private static String getClientIp(HttpExchange exchange) {
        String forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            return forwarded.split(",")[0].trim();
        }
        return exchange.getRemoteAddress().getAddress().getHostAddress();
    }

    private static boolean rateLimitOk(String ip) {
        RequestCounter counter = rateLimiter.computeIfAbsent(ip, k -> new RequestCounter());
        int count = counter.increment();
        if (count > 60) {
            return false;
        }
        // Reset after 60s (naive: ponytail: for multi-instance use shared store)
        new Thread(() -> {
            try { Thread.sleep(60000); counter.reset(); } catch (InterruptedException ignored) {}
        }).start();
        return true;
    }

    private static void sendError(HttpExchange exchange, int code, String msg) throws IOException {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("error", msg);
        byte[] resp = MAPPER.writeValueAsBytes(node);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, resp.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
    }

    private static class RequestCounter {
        private final AtomicInteger count = new AtomicInteger(0);
        int increment() { return count.incrementAndGet(); }
        void reset() { count.set(0); }
    }
}
