package me.ghosthacks96.POS_System.Storage;

import me.ghosthacks96.POS_System.utilities.Logger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;

public final class MySQL_Interface extends JdbcStorage {
    private final String url;
    private final String username;
    private final String password;

    public MySQL_Interface(String host, int port, String username, String password, String database, Logger logger) {
        super(logger);
        if (host == null || host.isBlank() || database == null || database.isBlank()) {
            throw new IllegalArgumentException("MySQL host and database are required");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("MySQL port must be between 1 and 65535");
        }
        this.url = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?serverTimezone=UTC&characterEncoding=UTF-8";
        this.username = Objects.requireNonNull(username, "username");
        this.password = Objects.requireNonNull(password, "password");
    }

    @Override
    protected Connection openConnection() throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }

    @Override
    protected String idDefinition() {
        return "BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY";
    }

    @Override
    protected String longType() {
        return "BIGINT";
    }

    @Override
    protected String bootstrapRowSql() {
        return "INSERT IGNORE INTO system_user_bootstrap (id) VALUES (1)";
    }

    @Override
    protected void lockBootstrapRow(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT id FROM system_user_bootstrap WHERE id=1 FOR UPDATE");
             var result = statement.executeQuery()) {
            if (!result.next()) {
                throw new SQLException("User bootstrap lock row is missing; reinitialize the database");
            }
        }
    }
}
