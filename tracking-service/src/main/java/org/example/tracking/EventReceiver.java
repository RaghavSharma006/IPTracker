package org.example.tracking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import org.example.db.Database;
import org.example.db.Database.EventRecord;
import org.example.db.Database.TokenRecord;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class EventReceiver {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INTERNAL_SECRET = System.getenv().getOrDefault("INTERNAL_API_SECRET", "");

    // ponytail: rate limit internal endpoint
    private static final ConcurrentHashMap<String, AtomicInteger> rateLimiter = new ConcurrentHashMap<>();

    public static void handle(HttpExchange exchange) {
        try {
            // Read body once — needed for both HMAC verification and event processing
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            // Authenticate via HMAC signature
            if (!verifySignature(exchange, body)) {
                sendError(exchange, 401, "Unauthorized");
                return;
            }

            // Rate limit
            String clientIp = exchange.getRemoteAddress().getAddress().getHostAddress();
            if (!rateLimitOk(clientIp)) {
                sendError(exchange, 429, "Too many requests");
                return;
            }
            JsonNode req = MAPPER.readTree(body);

            String token = req.path("token").asText("");
            String ip = req.path("ip").asText("");
            String ipVersion = req.path("ip_version").asText("");
            String userAgent = req.path("user_agent").asText("");
            String referer = req.path("referer").asText("");
            Instant timestamp = Instant.parse(req.path("timestamp").asText(Instant.now().toString()));

            TokenRecord record = Database.findByToken(token);
            if (record == null) {
                sendError(exchange, 404, "Token not found");
                return;
            }

            // Determine if IP is private — skip geolocation for those
            String country = null, region = null, city = null, tz = null, asn = null, org = null, isp = null, privacy = null;
            Double latitude = null, longitude = null;

            if (!isPrivate(ip)) {
                try {
                    IpLookup.Result ipInfo = IpLookup.lookup(ip);
                    if (ipInfo != null) {
                        country = ipInfo.country();
                        region = ipInfo.region();
                        city = ipInfo.city();
                        tz = ipInfo.timezone();
                        asn = ipInfo.asn();
                        org = ipInfo.org();
                        isp = ipInfo.isp();
                        privacy = ipInfo.privacy();
                        latitude = ipInfo.latitude();
                        longitude = ipInfo.longitude();
                    }
                } catch (Exception e) {
                    System.err.println("[IPLOOKUP] Failed for " + ip + ": " + e.getMessage());
                }
            }

            EventRecord event = new EventRecord(
                    record.id(), timestamp.toString(), ip, ipVersion,
                    country, region, city, latitude, longitude,
                    tz, asn, org, isp, privacy, userAgent, referer
            );
            Database.insertEvent(event);

            System.out.println("[EVENT] Stored event for token=" + token + " from " + ip);

            // Send email notification async
            new Thread(() -> {
                try {
                    EmailSender.sendAlert(record, event);
                } catch (Exception e) {
                    System.err.println("[EMAIL ERROR] Failed to send email to " + record.notificationEmail() + ": " + e.getMessage());
                }
            }).start();

            // Acknowledge
            ObjectNode resp = MAPPER.createObjectNode();
            resp.put("status", "event stored");
            sendJson(exchange, 200, resp);
        } catch (Exception e) {
            try { sendError(exchange, 500, "Internal error"); } catch (Exception ignored) {}
        } finally {
            exchange.close();
        }
    }

    private static boolean verifySignature(HttpExchange exchange, String body) {
        if (INTERNAL_SECRET.isBlank()) {
            return false;
        }

        String signature = exchange.getRequestHeaders().getFirst("X-Signature");
        if (signature == null) return false;

        JsonNode req;
        try {
            req = MAPPER.readTree(body);
        } catch (Exception e) {
            return false;
        }

        String token = req.path("token").asText("");
        String ip = req.path("ip").asText("");
        String ts = req.path("timestamp").asText("");

        // Verify HMAC — use minutes-precision like sender
        Instant time = Instant.parse(ts);
        long minuteBucket = time.getEpochSecond() / 60;
        String msg = token + "|" + ip + "|" + minuteBucket;

        String expected = hmacSha256(INTERNAL_SECRET, msg);
        return signature.equals(expected);
    }

    private static String hmacSha256(String secret, String data) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(raw);
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean rateLimitOk(String ip) {
        AtomicInteger counter = rateLimiter.computeIfAbsent(ip, k -> new AtomicInteger(0));
        int count = counter.incrementAndGet();
        if (count > 200) return false;
        new Thread(() -> {
            try { Thread.sleep(60000); } catch (InterruptedException ignored) {}
            counter.set(0);
        }).start();
        return true;
    }

    private static boolean isPrivate(String ip) {
        try {
            InetAddress addr = InetAddress.getByName(ip);
            return addr.isAnyLocalAddress() || addr.isLoopbackAddress()
                    || addr.isLinkLocalAddress() || addr.isSiteLocalAddress();
        } catch (Exception e) {
            return false;
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
