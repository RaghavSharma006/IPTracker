package org.example;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Enumeration;

public class CanaryServer {

    private static final int PORT = 8080;

    private static final HttpClient HTTP_CLIENT =
            HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {

        HttpServer server = HttpServer.create(
                new InetSocketAddress("0.0.0.0", PORT),
                0
        );

        server.createContext("/t", CanaryServer::handleToken);

        server.start();

        System.out.println("=================================");
        System.out.println("      CANARY SERVER STARTED");
        System.out.println("=================================");
        System.out.println();
        System.out.println("Local:");
        System.out.println("http://localhost:" + PORT + "/t/abc123");
        System.out.println();
        System.out.println("Server IPv4 addresses:");
        printServerIPv4();
        System.out.println();
        System.out.println("Server IPv6 addresses:");
        printServerIPv6();
        System.out.println();
    }

    private static void handleToken(HttpExchange exchange) {

        try {

            String path =
                    exchange.getRequestURI().getPath();

            String token = extractToken(path);

            InetAddress remote =
                    exchange.getRemoteAddress().getAddress();

            String ip =
                    remote.getHostAddress();

            String userAgent =
                    exchange.getRequestHeaders()
                            .getFirst("User-Agent");

            String referer =
                    exchange.getRequestHeaders()
                            .getFirst("Referer");

            System.out.println();
            System.out.println("=================================");
            System.out.println("🚨 CANARY TRIGGERED");
            System.out.println("=================================");

            System.out.println("Time: " + Instant.now());

            System.out.println("Token: " + token);

            System.out.println("IP: " + ip);

            System.out.println(
                    "IP Version: " +
                            getIPVersion(remote)
            );

            System.out.println(
                    "Hostname: " +
                            getHostname(remote)
            );

            System.out.println(
                    "User-Agent: " +
                            safe(userAgent)
            );

            System.out.println(
                    "Referer: " +
                            safe(referer)
            );

            System.out.println();

            if (isPublicIP(remote)) {

                System.out.println(
                        "Looking up public IP information..."
                );

                lookupIP(ip);

            } else {

                System.out.println(
                        "Private/loopback IP detected."
                );

                System.out.println(
                        "Skipping public IP lookup."
                );
            }

            System.out.println(
                    "================================="
            );

            // Don't return any useful content.
            exchange.sendResponseHeaders(204, -1);

        } catch (Exception e) {

            System.err.println(
                    "Error processing request: "
                            + e.getMessage()
            );

            try {
                exchange.sendResponseHeaders(500, -1);
            } catch (IOException ignored) {
            }

        } finally {

            exchange.close();
        }
    }

    private static String extractToken(String path) {

        if (path.startsWith("/t/")) {

            return path.substring(3);
        }

        return "unknown";
    }

    private static String getIPVersion(InetAddress address) {

        if (address instanceof Inet4Address) {
            return "IPv4";
        }

        if (address instanceof Inet6Address) {
            return "IPv6";
        }

        return "Unknown";
    }

    private static String getHostname(InetAddress address) {

        try {

            return address.getCanonicalHostName();

        } catch (Exception e) {

            return "Unavailable";
        }
    }

    private static boolean isPublicIP(InetAddress address) {

        return !address.isAnyLocalAddress()
                && !address.isLoopbackAddress()
                && !address.isLinkLocalAddress()
                && !address.isSiteLocalAddress()
                && !address.isMulticastAddress();
    }

    private static void lookupIP(String ip)
            throws IOException, InterruptedException {

        /*
         * IPinfo's legacy JSON endpoint is convenient for a
         * small test project.
         *
         * For production/current integrations, use the
         * current IPinfo API and its authentication model.
         */

        String url =
                "https://ipinfo.io/"
                        + URLEncoder.encode(
                        ip,
                        java.nio.charset.StandardCharsets.UTF_8
                )
                        + "/json";

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Accept", "application/json")
                        .GET()
                        .build();

        HttpResponse<String> response =
                HTTP_CLIENT.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );

        System.out.println(
                "HTTP Status: "
                        + response.statusCode()
        );

        if (response.statusCode() == 200) {

            printJSON(response.body());

        } else {

            System.out.println(
                    "IP lookup failed:"
            );

            System.out.println(
                    response.body()
            );
        }
    }

    private static void printJSON(String json) {

        /*
         * For now we print the JSON directly.
         *
         * Later you can parse this with Jackson and turn
         * every field into a proper Java object.
         */

        System.out.println();
        System.out.println("IP INFORMATION");
        System.out.println("-------------------------");
        System.out.println(json);
    }

    private static String safe(String value) {

        if (value == null || value.isBlank()) {
            return "Not provided";
        }

        return value;
    }

    private static void printServerIPv4()
            throws SocketException {

        Enumeration<NetworkInterface> interfaces =
                NetworkInterface.getNetworkInterfaces();

        while (interfaces.hasMoreElements()) {

            NetworkInterface networkInterface =
                    interfaces.nextElement();

            if (!networkInterface.isUp()
                    || networkInterface.isLoopback()) {
                continue;
            }

            Enumeration<InetAddress> addresses =
                    networkInterface.getInetAddresses();

            while (addresses.hasMoreElements()) {

                InetAddress address =
                        addresses.nextElement();

                if (address instanceof Inet4Address) {

                    System.out.println(
                            "  "
                                    + address.getHostAddress()
                    );
                }
            }
        }
    }

    private static void printServerIPv6()
            throws SocketException {

        Enumeration<NetworkInterface> interfaces =
                NetworkInterface.getNetworkInterfaces();

        while (interfaces.hasMoreElements()) {

            NetworkInterface networkInterface =
                    interfaces.nextElement();

            if (!networkInterface.isUp()
                    || networkInterface.isLoopback()) {
                continue;
            }

            Enumeration<InetAddress> addresses =
                    networkInterface.getInetAddresses();

            while (addresses.hasMoreElements()) {

                InetAddress address =
                        addresses.nextElement();

                if (address instanceof Inet6Address) {

                    System.out.println(
                            "  "
                                    + address.getHostAddress()
                    );
                }
            }
        }
    }
}