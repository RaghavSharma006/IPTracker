package org.example.db;

import java.sql.*;

public class Database {

    private static final String DB_URL = System.getenv().getOrDefault(
            "DATABASE_URL", "jdbc:sqlite:redirect.db"
    );

    public static void init() throws SQLException {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {
            // ponytail: redirect service only needs tokens table for lookup.
            // Full schema (users, events) lives in tracking-service.
            // We replicate a read-only tokens table locally for fast lookups
            // without hitting Service 2 on every redirect.
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS tokens (
                    id               INTEGER PRIMARY KEY AUTOINCREMENT,
                    token            TEXT    NOT NULL UNIQUE,
                    destination_url  TEXT    NOT NULL,
                    enabled          INTEGER NOT NULL DEFAULT 1,
                    created_at       TEXT    NOT NULL DEFAULT (datetime('now')),
                    updated_at       TEXT
                )
                """);
        }
    }

    public static boolean tokenExists(String token) throws SQLException {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM tokens WHERE token = ? AND enabled = 1")) {
            ps.setString(1, token);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public static String getDestination(String token) throws SQLException {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT destination_url FROM tokens WHERE token = ? AND enabled = 1")) {
            ps.setString(1, token);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("destination_url") : null;
            }
        }
    }
}
