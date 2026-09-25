package org.example.db;

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
                    token       TEXT PRIMARY KEY,
                    email       TEXT NOT NULL,
                    created_at  TEXT NOT NULL DEFAULT (datetime('now'))
                )
                """);
        }
    }

    public static TokenRecord createToken(String email) throws SQLException {
        String token = java.util.UUID.randomUUID().toString();
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tokens (token, email) VALUES (?, ?)")) {
            ps.setString(1, token);
            ps.setString(2, email);
            ps.executeUpdate();
        }
        return new TokenRecord(token, email);
    }

    public static TokenRecord findByToken(String token) throws SQLException {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT token, email FROM tokens WHERE token = ?")) {
            ps.setString(1, token);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return new TokenRecord(rs.getString("token"), rs.getString("email"));
            }
            return null;
        }
    }

    public record TokenRecord(String token, String email) {}
}
