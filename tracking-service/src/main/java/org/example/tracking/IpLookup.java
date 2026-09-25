package org.example.tracking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

public class IpLookup {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String API_KEY = System.getenv().getOrDefault("IP_LOOKUP_API_KEY", "");
    private static final String URL_TEMPLATE = API_KEY.isBlank()
            ? "https://ipinfo.io/{ip}/json"
            : "https://ipinfo.io/{ip}/json?token={key}";

    public static Result lookup(String ip) throws Exception {
        if (API_KEY.isBlank()) {
            // No API key — do unauthenticated lookup (limited)
            String url = URL_TEMPLATE.replace("{ip}", URLEncoder.encode(ip, StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;
            return parse(resp.body());
        }

        String url = URL_TEMPLATE.replace("{ip}", URLEncoder.encode(ip, StandardCharsets.UTF_8))
                .replace("{key}", URLEncoder.encode(API_KEY, StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            System.err.println("[IPLOOKUP] API returned " + resp.statusCode());
            return null;
        }
        return parse(resp.body());
    }

    private static Result parse(String json) throws Exception {
        JsonNode node = MAPPER.readTree(json);
        String country = node.path("country").asText(null);
        String region = node.path("region").asText(null);
        String city = node.path("city").asText(null);
        String timezone = node.path("timezone").asText(null);
        String asn = null;
        String org = node.path("org").asText(null);
        String isp = node.path("isp").asText(null);
        String privacy = null;

        JsonNode asnNode = node.path("asn");
        if (asnNode.isObject()) {
            asn = asnNode.path("asn").asText(null);
        } else if (asnNode.isTextual()) {
            asn = asnNode.asText();
        }

        JsonNode privacyNode = node.path("privacy");
        if (privacyNode.isObject()) {
            privacy = privacyNode.toString();
        }

        Double latitude = null, longitude = null;
        String loc = node.path("loc").asText(null);
        if (loc != null && loc.contains(",")) {
            String[] parts = loc.split(",");
            latitude = Double.parseDouble(parts[0]);
            longitude = Double.parseDouble(parts[1]);
        }

        return new Result(country, region, city, latitude, longitude, timezone, asn, org, isp, privacy);
    }

    public record Result(String country, String region, String city, Double latitude,
                         Double longitude, String timezone, String asn, String org,
                         String isp, String privacy) {}
}
