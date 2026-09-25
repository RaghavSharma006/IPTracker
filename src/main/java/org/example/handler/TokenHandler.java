package org.example.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import org.example.db.Database;
import org.example.db.Database.EventRecord;
import org.example.db.Database.TokenRecord;
import org.example.email.EmailSender;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public class TokenHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    // ponytail: in-memory rate limiter (100 req/min per IP), good enough for a single-instance deploy
    private static final ConcurrentHashMap<String, AtomicInteger> rateLimit = new ConcurrentHashMap<>();

    private static final Pattern URL_PATTERN = Pattern.compile(
            "^https?://[a-zA-Z0-9.\\-]+(:\\d+)?(/.*)?$"
    );

    private static final String DOMAIN = System.getenv().getOrDefault(
            "DOMAIN", System.getenv().getOrDefault("RAILWAY_PUBLIC_DOMAIN", "localhost:8080")
    );

    public static void handleCreateToken(HttpExchange exchange) {
        try {
            // Rate limit
            String clientIp = exchange.getRemoteAddress().getAddress().getHostAddress();
            if (!rateLimitOk("create:" + clientIp)) {
                sendError(exchange, 429, "Too many requests");
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            ObjectNode req = (ObjectNode) MAPPER.readTree(body);
            String destinationUrl = req.path("destination_url").asText("").trim();
            String notificationEmail = req.path("notification_email").asText("").trim();

            if (destinationUrl.isEmpty() || notificationEmail.isEmpty()) {
                sendError(exchange, 400, "destination_url and notification_email are required");
                return;
            }

            if (!URL_PATTERN.matcher(destinationUrl).matches()) {
                sendError(exchange, 400, "Only http:// and https:// URLs are allowed");
                return;
            }

            TokenRecord record = Database.createToken(destinationUrl, notificationEmail);

            String protocol = System.getenv().getOrDefault("PROTOCOL", "https");
            String link = protocol + "://" + DOMAIN + "/r/" + record.token();

            ObjectNode resp = MAPPER.createObjectNode();
            resp.put("token", record.token());
            resp.put("destination_url", record.destinationUrl());
            resp.put("notification_email", record.notificationEmail());
            resp.put("link", link);
            resp.put("created_at", record.createdAt());

            sendJson(exchange, 200, resp);
        } catch (Exception e) {
            try {
                sendError(exchange, 500, e.getMessage());
            } catch (Exception ignored) {}
        } finally {
            exchange.close();
        }
    }

    public static void handleRedirect(HttpExchange exchange, EmailSender emailSender) {
        try {
            String path = exchange.getRequestURI().getPath();
            String token = path.startsWith("/r/") ? path.substring(3) : "unknown";

            // Rate limit per token (100/min)
            if (!rateLimitOk("track:" + token)) {
                sendError(exchange, 429, "Too many requests");
                return;
            }

            TokenRecord record = Database.findByToken(token);
            if (record == null) {
                sendError(exchange, 404, "Tracking link not found");
                return;
            }

            // Capture request data BEFORE redirecting
            InetAddress remote = exchange.getRemoteAddress().getAddress();
            String ip = remote.getHostAddress();
            String ipVersion = remote instanceof java.net.Inet4Address ? "IPv4" :
                    remote instanceof java.net.Inet6Address ? "IPv6" : "Unknown";
            String userAgent = exchange.getRequestHeaders().getFirst("User-Agent");
            String referer = exchange.getRequestHeaders().getFirst("Referer");
            Instant time = Instant.now();

            // For public IPs, query IPinfo
            String country = null, region = null, city = null, tz = null, asn = null, org = null;
            String privacy = null;
            Double latitude = null, longitude = null;

            if (!isPrivateIP(remote)) {
                try {
                    String url = "https://ipinfo.io/" + URLEncoder.encode(ip, StandardCharsets.UTF_8) + "/json";
                    HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).build();
                    HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() == 200) {
                        ObjectNode ipInfo = (ObjectNode) MAPPER.readTree(resp.body());
                        country = ipInfo.path("country").asText(null);
                        region = ipInfo.path("region").asText(null);
                        city = ipInfo.path("city").asText(null);
                        tz = ipInfo.path("timezone").asText(null);
                        asn = ipInfo.path("asn").asText(null);
                        org = ipInfo.path("org").asText(null);
                        privacy = ipInfo.path("privacy").asText(null);

                        String loc = ipInfo.path("loc").asText(null);
                        if (loc != null && loc.contains(",")) {
                            String[] parts = loc.split(",");
                            latitude = Double.parseDouble(parts[0]);
                            longitude = Double.parseDouble(parts[1]);
                        }
                    }
                    System.out.println("[IPINFO] Queried " + ip + " -> " + country + "/" + city);
                } catch (Exception e) {
                    System.err.println("[IPINFO ERROR] " + e.getMessage());
                }
            }

            // Record event
            EventRecord event = new EventRecord(
                    record.id(), time.toString(), ip, ipVersion,
                    country, region, city, latitude, longitude,
                    tz, asn, org,
                    userAgent, referer, privacy
            );
            Database.insertEvent(event);

            System.out.println("🚨 Tracking link triggered: " + token + " from " + ip);

            // Send email asynchronously
            new Thread(() -> emailSender.sendRedirectAlert(
                    record.notificationEmail(), token, record.destinationUrl(),
                    ip, ipVersion, userAgent, time
            )).start();

            // 302 redirect to stored destination
            exchange.getResponseHeaders().add("Location", record.destinationUrl());
            exchange.sendResponseHeaders(302, -1);
        } catch (Exception e) {
            try {
                sendError(exchange, 500, e.getMessage());
            } catch (Exception ignored) {}
        } finally {
            exchange.close();
        }
    }

    public static void handleGetEvents(HttpExchange exchange) {
        try {
            // ponytail: simple token-based admin — token passed as query param "key"
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

            String path = exchange.getRequestURI().getPath();
            String token = path.startsWith("/api/tokens/") ? path.substring("/api/tokens/".length()) : "";

            TokenRecord record = Database.findByToken(token);
            if (record == null) {
                sendError(exchange, 404, "Token not found");
                return;
            }

            // Authorization: only the token owner can view events.
            // They authenticate by providing the token itself as ?key=<token>
            if (key == null || !key.equals(token)) {
                sendError(exchange, 403, "Unauthorized");
                return;
            }

            // Return token info + placeholder for events (events require token_id lookup)
            ObjectNode resp = MAPPER.createObjectNode();
            resp.put("token", record.token());
            resp.put("destination_url", record.destinationUrl());
            resp.put("notification_email", record.notificationEmail());
            resp.put("created_at", record.createdAt());
            // Note: events retrieval would go here
            sendJson(exchange, 200, resp);
        } catch (Exception e) {
            try {
                sendError(exchange, 500, e.getMessage());
            } catch (Exception ignored) {}
        } finally {
            exchange.close();
        }
    }

    private static boolean rateLimitOk(String key) {
        AtomicInteger counter = rateLimit.computeIfAbsent(key, k -> new AtomicInteger(0));
        // Reset every min (simplified)
        int count = counter.incrementAndGet();
        if (count > 100) {
            return false;
        }
        // Reset after 60s (ponytail: naive, uses thread sleep; for prod use scheduled executor)
        new Thread(() -> {
            try { Thread.sleep(60000); } catch (InterruptedException ignored) {}
            counter.set(0);
        }).start();
        return true;
    }

    private static boolean isPrivateIP(InetAddress address) {
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress();
    }

    private static void sendJson(HttpExchange exchange, int code, ObjectNode node) throws IOException {
        byte[] resp = MAPPER.writeValueAsBytes(node);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, resp.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(resp);
        }
    }

    private static void sendError(HttpExchange exchange, int code, String msg) throws IOException {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("error", msg);
        byte[] resp = MAPPER.writeValueAsBytes(node);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, resp.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(resp);
        }
    }
}
