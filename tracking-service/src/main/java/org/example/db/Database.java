package org.example.db;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class Database {

    private static final String DB_URL = System.getenv().getOrDefault(
            "DATABASE_URL", "jdbc:sqlite:tracking.db"
    );

    public static void init() throws SQLException {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    email      TEXT    NOT NULL UNIQUE,
                    created_at TEXT    NOT NULL DEFAULT (datetime('now'))
                )
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS tokens (
                    id               INTEGER PRIMARY KEY AUTOINCREMENT,
                    token            TEXT    NOT NULL UNIQUE,
                    user_id          INTEGER NOT NULL,
                    destination_url  TEXT    NOT NULL,
                    created_at       TEXT    NOT NULL DEFAULT (datetime('now')),
                    enabled          INTEGER NOT NULL DEFAULT 1,
                    FOREIGN KEY (user_id) REFERENCES users(id)
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
                    isp           TEXT,
                    privacy       TEXT,
                    user_agent    TEXT,
                    referer       TEXT,
                    FOREIGN KEY (token_id) REFERENCES tokens(id)
                )
                """);

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_events_token ON events(token_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_tokens_user ON tokens(user_id)");
        }
    }

    public static long findOrCreateUser(String email) throws SQLException {
        // Try to find existing user first
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id FROM users WHERE email = ?")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        // Insert new user
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (email) VALUES (?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, email);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        throw new SQLException("Failed to find or create user");
    }

    public static TokenRecord createToken(String destinationUrl, String email) throws SQLException {
        long userId = findOrCreateUser(email);
        String token = generateSecureToken();

        String createdAt = Instant.now().toString();
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tokens (token, user_id, destination_url, created_at, enabled) " +
                             "VALUES (?, ?, ?, ?, 1)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, token);
            ps.setLong(2, userId);
            ps.setString(3, destinationUrl);
            ps.setString(4, createdAt);
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    return new TokenRecord(id, token, destinationUrl, email, userId, createdAt);
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
                     "SELECT t.id, t.token, t.destination_url, u.email, t.user_id, t.created_at " +
                             "FROM tokens t JOIN users u ON t.user_id = u.id WHERE t.token = ? AND t.enabled = 1")) {
            ps.setString(1, token);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new TokenRecord(rs.getLong("id"), rs.getString("token"),
                            rs.getString("destination_url"), rs.getString("email"),
                            rs.getLong("user_id"), rs.getString("created_at"));
                }
            }
        }
        return null;
    }

    public static long insertEvent(EventRecord event) throws SQLException {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO events (token_id, timestamp, ip, ip_version, country, region, city, " +
                             "latitude, longitude, timezone, asn, organization, isp, privacy, user_agent, referer) " +
                             "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
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
            ps.setString(13, event.isp());
            ps.setString(14, event.privacy());
            ps.setString(15, event.userAgent());
            ps.setString(16, event.referer());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        }
    }

    public static List<EventRecord> getEventsByToken(String token) throws SQLException {
        List<EventRecord> events = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT e.* FROM events e JOIN tokens t ON e.token_id = t.id WHERE t.token = ? ORDER BY e.timestamp DESC")) {
            ps.setString(1, token);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    events.add(new EventRecord(
                            rs.getLong("token_id"),
                            rs.getString("timestamp"),
                            rs.getString("ip"),
                            rs.getString("ip_version"),
                            rs.getString("country"), rs.getString("region"), rs.getString("city"),
                            (Double) rs.getObject("latitude"), (Double) rs.getObject("longitude"),
                            rs.getString("timezone"), rs.getString("asn"), rs.getString("organization"),
                            rs.getString("isp"), rs.getString("privacy"),
                            rs.getString("user_agent"), rs.getString("referer")
                    ));
                }
            }
        }
        return events;
    }

    // Records
    public record TokenRecord(long id, String token, String destinationUrl,
                              String notificationEmail, long userId, String createdAt) {}

    public record EventRecord(long tokenId, String timestamp, String ip, String ipVersion,
                              String country, String region, String city, Double latitude,
                              Double longitude, String timezone, String asn, String organization,
                              String isp, String privacy, String userAgent, String referer) {}
}
