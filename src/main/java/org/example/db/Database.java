package org.example.db;

import java.net.InetAddress;
import java.sql.*;

public class Database {

    private static final String DB_URL = System.getenv().getOrDefault(
            "DATABASE_URL", "jdbc:sqlite:canary.db"
    );

    public static void init() throws SQLException {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS tokens (
                    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
                    token                TEXT    NOT NULL UNIQUE,
                    destination_url      TEXT    NOT NULL,
                    notification_email   TEXT    NOT NULL,
                    created_at           TEXT    NOT NULL DEFAULT (datetime('now'))
                )
                """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS events (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    token_id      INTEGER NOT NULL,
                    timestamp     TEXT    NOT NULL,
                    ip            TEXT    NOT NULL,
                    ip_version    TEXT    NOT NULL,
                    country       TEXT,
                    region        TEXT,
                    city          TEXT,
                    latitude      REAL,
                    longitude     REAL,
                    timezone      TEXT,
                    asn           TEXT,
                    organization  TEXT,
                    user_agent    TEXT,
                    referer       TEXT,
                    privacy       TEXT,
                    FOREIGN KEY (token_id) REFERENCES tokens(id)
                )
                """);
            // Index for fast token lookups
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_events_token ON events(token_id)");
        }
    }

    public static TokenRecord createToken(String destinationUrl, String notificationEmail) throws SQLException {
        String token = generateSecureToken();
        String createdAt = java.time.Instant.now().toString();
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tokens (token, destination_url, notification_email, created_at) VALUES (?, ?, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, token);
            ps.setString(2, destinationUrl);
            ps.setString(3, notificationEmail);
            ps.setString(4, createdAt);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return new TokenRecord(rs.getLong(1), token, destinationUrl, notificationEmail, createdAt);
                }
            }
        }
        throw new SQLException("Failed to create token");
    }

    private static String generateSecureToken() {
        java.security.SecureRandom sr = new java.security.SecureRandom();
        byte[] bytes = new byte[16];
        sr.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static TokenRecord findByToken(String token) throws SQLException {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, token, destination_url, notification_email, created_at FROM tokens WHERE token = ?")) {
            ps.setString(1, token);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return new TokenRecord(rs.getLong("id"), rs.getString("token"),
                        rs.getString("destination_url"), rs.getString("notification_email"),
                        rs.getString("created_at"));
            }
            return null;
        }
    }

    public static void insertEvent(EventRecord event) throws SQLException {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO events (token_id, timestamp, ip, ip_version, country, region, city, " +
                             "latitude, longitude, timezone, asn, organization, user_agent, referer, privacy) " +
                             "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, event.tokenId());
            ps.setString(2, event.timestamp());
            ps.setString(3, event.ip());
            ps.setString(4, event.ipVersion());
            ps.setString(5, event.country());
            ps.setString(6, event.region());
            ps.setString(7, event.city());
            ps.setObject(8, event.latitude());
            ps.setObject(9, event.longitude());
            ps.setString(10, event.timezone());
            ps.setString(11, event.asn());
            ps.setString(12, event.organization());
            ps.setString(13, event.userAgent());
            ps.setString(14, event.referer());
            ps.setString(15, event.privacy());
            ps.executeUpdate();
        }
    }

    // Records
    public record TokenRecord(long id, String token, String destinationUrl,
                              String notificationEmail, String createdAt) {}

    public record EventRecord(long tokenId, String timestamp, String ip, String ipVersion,
                              String country, String region, String city, Double latitude,
                              Double longitude, String timezone, String asn, String organization,
                              String userAgent, String referer, String privacy) {}
}
