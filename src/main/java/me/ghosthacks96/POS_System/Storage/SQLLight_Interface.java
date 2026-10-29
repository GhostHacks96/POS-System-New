package me.ghosthacks96.POS_System.Storage;

import me.ghosthacks96.POS_System.utilities.Logger;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;

public final class SQLLight_Interface extends JdbcStorage {
    private final Path databaseFile;

    public SQLLight_Interface(Path databaseFile, Logger logger) {
        super(logger);
        this.databaseFile = Objects.requireNonNull(databaseFile, "databaseFile")
                .toAbsolutePath()
                .normalize();
    }

    public Path getDatabaseFile() {
        return databaseFile;
    }

    @Override
    protected Connection openConnection() throws SQLException {
        Path parent = databaseFile.getParent();
        if (parent != null) {
            try {
                java.nio.file.Files.createDirectories(parent);
            } catch (java.io.IOException exception) {
                throw new SQLException("Unable to create SQLite database directory: " + parent, exception);
            }
        }
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
        } catch (SQLException exception) {
            connection.close();
            throw exception;
        }
        return connection;
    }

    @Override
    protected String idDefinition() {
        return "INTEGER PRIMARY KEY AUTOINCREMENT";
    }

    @Override
    protected String longType() {
        return "INTEGER";
    }

    @Override
    protected String bootstrapRowSql() {
        return "INSERT OR IGNORE INTO system_user_bootstrap (id) VALUES (1)";
    }

    @Override
    protected void lockBootstrapRow(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE system_user_bootstrap SET id=id WHERE id=1")) {
            if (statement.executeUpdate() != 1) {
                throw new SQLException("User bootstrap lock row is missing; reinitialize the database");
            }
        }
    }
}
