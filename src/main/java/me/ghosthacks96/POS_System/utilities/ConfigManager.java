package me.ghosthacks96.POS_System.utilities;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class ConfigManager {
    private final Path directory;
    private final Logger logger;
    private final Yaml yaml;
    private final Map<Path, AbstractConfig> configs = new LinkedHashMap<>();

    public ConfigManager(Logger logger) {
        this(Path.of("."), logger);
    }

    public ConfigManager(Path directory, Logger logger) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        this.logger = Objects.requireNonNull(logger, "logger");

        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setAllowDuplicateKeys(false);
        DumperOptions dumperOptions = new DumperOptions();
        dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        dumperOptions.setPrettyFlow(true);
        yaml = new Yaml(
                new SafeConstructor(loaderOptions),
                new Representer(dumperOptions),
                dumperOptions,
                loaderOptions
        );
    }


    public synchronized <T extends AbstractConfig> T register(T config) {
        Objects.requireNonNull(config, "config");
        Path file = resolveFile(config.getFileName());
        logger.debug("Config: registering " + config.getFileName());
        AbstractConfig existing = configs.get(file);
        if (existing != null) {
            if (existing == config) {
                return config;
            }
            throw new IllegalStateException("A configuration is already registered for " + file);
        }

        config.bind(this, file);
        try {
            if (Files.exists(file)) {
                logger.debug("Config: reading existing file " + config.getFileName());
                config.replaceValues(readValues(file));
            } else {
                logger.debug("Config: creating defaults for " + config.getFileName());
                config.loadDefaults();
                writeValues(file, config.snapshotValues());
            }
            configs.put(file, config);
            logger.debug("Config: registered " + config.getFileName());
            return config;
        } catch (RuntimeException exception) {
            config.unbind();
            throw exception;
        }
    }

    public synchronized void saveAll() {
        logger.debug("Config: saving " + configs.size() + " registered configuration files");
        for (AbstractConfig config : configs.values()) {
            save(config);
        }
    }

    public synchronized void reloadAll() {
        logger.debug("Config: reloading " + configs.size() + " registered configuration files");
        for (AbstractConfig config : configs.values()) {
            reload(config);
        }
        logger.debug("Config: all registered files reloaded");
    }

    public synchronized int getRegisteredCount() {
        return configs.size();
    }

    synchronized void save(AbstractConfig config) {
        requireRegistered(config);
        logger.debug("Config: saving " + config.getFileName());
        writeValues(config.getFile(), config.snapshotValues());
        logger.debug("Config: saved " + config.getFileName());
    }

    synchronized void reload(AbstractConfig config) {
        requireRegistered(config);
        Path file = config.getFile();
        logger.debug("Config: reloading " + config.getFileName());
        if (!Files.exists(file)) {
            config.replaceValues(new LinkedHashMap<>());
            config.loadDefaults();
            writeValues(file, config.snapshotValues());
            logger.debug("Config: recreated missing file " + config.getFileName());
            return;
        }
        config.replaceValues(readValues(file));
        logger.debug("Config: reloaded " + config.getFileName());
    }

    private Map<String, Object> readValues(Path file) {
        try {
            Object loaded = yaml.load(Files.readString(file, StandardCharsets.UTF_8));
            if (loaded == null) {
                return new LinkedHashMap<>();
            }
            if (!(loaded instanceof Map<?, ?> map)) {
                throw new IllegalStateException("The YAML configuration root must be a mapping: " + file);
            }
            return AbstractConfig.stringKeyedMap(map);
        } catch (IOException exception) {
            throw reportError(
                    "Unable to read configuration file: " + file,
                    new UncheckedIOException("Unable to read configuration file: " + file, exception)
            );
        } catch (RuntimeException exception) {
            throw reportError("Unable to load configuration file: " + file, exception);
        }
    }

    private void writeValues(Path file, Map<String, Object> values) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, yaml.dump(values), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw reportError(
                    "Unable to save configuration file: " + file,
                    new UncheckedIOException("Unable to save configuration file: " + file, exception)
            );
        } catch (RuntimeException exception) {
            throw reportError("Unable to save configuration file: " + file, exception);
        }
    }

    private Path resolveFile(String fileName) {
        Path relativeFile = Path.of(fileName);
        Path file = directory.resolve(relativeFile).normalize();
        if (relativeFile.isAbsolute() || !file.startsWith(directory)) {
            throw new IllegalArgumentException("Configuration file must be inside the config directory: " + fileName);
        }
        String lowerCaseName = file.getFileName().toString().toLowerCase();
        if (!lowerCaseName.endsWith(".yml") && !lowerCaseName.endsWith(".yaml")) {
            throw new IllegalArgumentException("Configuration files must use a .yml or .yaml extension: " + fileName);
        }
        return file;
    }

    private void requireRegistered(AbstractConfig config) {
        Objects.requireNonNull(config, "config");
        if (config.getManager() != this || configs.get(config.getFile()) != config) {
            throw new IllegalArgumentException("Configuration is not registered with this manager");
        }
    }

    private <T extends RuntimeException> T reportError(String message, T exception) {
        logger.severe(message + ": " + exception.getMessage());
        return exception;
    }
}
