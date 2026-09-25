package org.example.email;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Base64;

public class EmailSender {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // SendGrid API endpoint
    private static final String SENDGRID_URL = "https://api.sendgrid.com/v3/mail/send";

    private final String apiKey;
    private final String fromEmail;

    public EmailSender() {
        this.apiKey = System.getenv().getOrDefault("SENDGRID_API_KEY", "");
        this.fromEmail = System.getenv().getOrDefault("FROM_EMAIL", "alerts@canary.local");
    }

    public boolean sendAlert(String toEmail, String token, String ip,
                             String userAgent, Instant time) {
        if (apiKey.isBlank()) {
            System.err.println("[WARN] SENDGRID_API_KEY not set, skipping email");
            return false;
        }

        try {
            ObjectNode body = MAPPER.createObjectNode();

            ObjectNode from = MAPPER.createObjectNode();
            from.put("email", fromEmail);
            body.set("from", from);

            ObjectNode to = MAPPER.createObjectNode();
            to.put("email", toEmail);

            ObjectNode personalization = MAPPER.createObjectNode();
            personalization.set("to", MAPPER.createArrayNode().add(to));
            body.set("personalizations", MAPPER.createArrayNode().add(personalization));

            body.put("subject", "🚨 Canary token opened");

            ObjectNode content = MAPPER.createObjectNode();
            content.put("type", "text/plain");
            content.put("value", formatEmail(token, ip, userAgent, time));
            body.set("content", MAPPER.createArrayNode().add(content));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(SENDGRID_URL))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> resp = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 202 || resp.statusCode() == 200) {
                System.out.println("[EMAIL] Alert sent to " + toEmail);
                return true;
            } else {
                System.err.println("[EMAIL ERROR] " + resp.statusCode() + " " + resp.body());
                return false;
            }
        } catch (Exception e) {
            System.err.println("[EMAIL ERROR] " + e.getMessage());
            return false;
        }
    }

    private String formatEmail(String token, String ip, String userAgent, Instant time) {
        return String.format("""
                Your canary token was opened!

                Token: %s
                Time:  %s
                IP:    %s
                UA:    %s
                """, token, time, ip, userAgent);
    }
}
