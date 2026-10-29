package me.ghosthacks96.POS_System.Storage;

import me.ghosthacks96.POS_System.utilities.Logger;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;

public final class StorageManager implements AutoCloseable {
    private final Path storagePath;
    private final Logger logger;
    private PosStorage storage;

    public StorageManager(Path storagePath, Logger logger) {
        this.storagePath = Objects.requireNonNull(storagePath, "storagePath").toAbsolutePath().normalize();
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public synchronized PosStorage loadStorage(
            String storageType,
            String host,
            int port,
            String username,
            String password,
            String database
    ) throws SQLException {
        Objects.requireNonNull(storageType, "storageType");
        PosStorage newStorage = switch (storageType.toLowerCase(Locale.ROOT)) {
            case "mysql" -> new MySQL_Interface(host, port, username, password, database, logger);
            case "sqlite", "sqlight" -> new SQLLight_Interface(storagePath.resolve("ghostpos.db"), logger);
            default -> throw new IllegalArgumentException(
                    "Unsupported storage type '" + storageType + "'. Choose mysql or sqlite."
            );
        };

        try {
            newStorage.initialize();
        } catch (SQLException exception) {
            try {
                newStorage.close();
            } catch (SQLException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }

        PosStorage previousStorage = storage;
        storage = newStorage;
        if (previousStorage != null) {
            previousStorage.close();
        }
        logger.info("Initialized " + storageType + " POS storage");
        return storage;
    }

    public synchronized PosStorage getStorage() {
        if (storage == null) {
            throw new IllegalStateException("Storage has not been loaded");
        }
        return storage;
    }

    @Override
    public synchronized void close() throws SQLException {
        if (storage != null) {
            storage.close();
            storage = null;
        }
    }
}
