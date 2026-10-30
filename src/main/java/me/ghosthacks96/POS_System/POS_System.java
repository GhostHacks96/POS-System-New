package me.ghosthacks96.POS_System;

import me.ghosthacks96.POS_System.Storage.StorageManager;
import me.ghosthacks96.POS_System.Inventory.InventoryService;
import me.ghosthacks96.POS_System.Api.ApiServer;
import me.ghosthacks96.POS_System.Sales.SalesService;
import me.ghosthacks96.POS_System.Web.ManagementWebServer;
import me.ghosthacks96.POS_System.Users.UserService;
import me.ghosthacks96.POS_System.Users.UserPrincipal;
import me.ghosthacks96.POS_System.utilities.AbstractConfig;
import me.ghosthacks96.POS_System.utilities.ConfigManager;
import me.ghosthacks96.POS_System.utilities.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.io.Console;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class POS_System {

    public static final Logger log = new Logger();
    public static final Path DATA_FOLDER = getDataFolder();
    public static final ConfigManager configManager = new ConfigManager(DATA_FOLDER, log);
    public static final StorageManager storageManager = new StorageManager(DATA_FOLDER.resolve("storage"), log);
    public static InventoryService inventoryService;
    public static SalesService salesService;
    public static UserService userService;
    public static AbstractConfig config;
    public static ApiServer apiServer;
    public static ManagementWebServer managementWebServer;


    //SystemVars
    public static final String VERSION = "1.0.0";
    public static final String SystemName = "GhostPOS";
    public static volatile boolean DEBUG = false;
    public static String StoreName = "Demo-Store";

    public static String StorageType,ip,user,pass,db;
    public static int port;
    private static volatile RuntimeConfig activeRuntimeConfig;


    static {
        Runtime.getRuntime().addShutdownHook(
                new Thread(POS_System::closeResources, "pos-system-shutdown")
        );
    }

    private static void closeResources() {
        log.debug("Shutdown: beginning graceful resource cleanup");
        if (managementWebServer != null) {
            try {
                log.debug("Shutdown: stopping management web interface");
                managementWebServer.close();
            } catch (RuntimeException exception) {
                log.severe("Unable to stop management web interface: " + exception.getClass().getSimpleName());
            }
        }
        if (apiServer != null) {
            try {
                log.debug("Shutdown: stopping register API");
                apiServer.close();
            } catch (RuntimeException exception) {
                log.severe("Unable to stop register API: " + exception.getClass().getSimpleName());
            }
        }
        try {
            log.debug("Shutdown: closing POS storage");
            storageManager.close();
        } catch (SQLException exception) {
            log.severe("Unable to close POS storage: " + exception.getMessage());
        } finally {
            log.debug("Shutdown: closing application logger");
            log.close();
        }
    }

    public static Path getDataFolder() {
        String appData = System.getenv("APPDATA");
        Path appDataFolder = appData == null || appData.isBlank()
                ? Path.of(System.getProperty("user.home"), "AppData", "Roaming")
                : Path.of(appData);
        Path dataFolder = appDataFolder.resolve("GhostPOS");
        try {
            return Files.createDirectories(dataFolder);
        } catch (IOException exception) {
            log.severe("Unable to create application data folder: " + dataFolder);
            throw new UncheckedIOException("Unable to create application data folder: " + dataFolder, exception);
        }
    }

    public static void main(String[] args){
        log.info(SystemName+" is starting up. Loading System Properties...");
        log.debug("Startup: registering application configuration");
        config = configManager.register(new AbstractConfig("config.yml") {
            @Override
            protected void loadDefaults() {
                set("debug", false);
                set("store-name", "Demo-Store");
                set("store-address", "");
                set("store-phone", "");
                set("storage-type", "sqlite");
                set("mysql.ip", "localhost");
                set("mysql.port", 3306);
                set("mysql.user", "root");
                set("mysql.pass", "");
                set("mysql.db", "ghostpos");
                set("api.enabled", true);
                set("api.host", "127.0.0.1");
                set("api.port", 7070);
                set("api.session-ttl-minutes", 60);
                set("web.enabled", true);
                set("web.host", "127.0.0.1");
                set("web.port", 8080);
                set("web.api-base-url", "");
                set("web.allowed-origins", List.of(
                        "http://127.0.0.1:8080",
                        "http://localhost:8080"
                ));
            }
        });

        RuntimeConfig runtimeConfig = readRuntimeConfig();
        log.debug("Startup: validated runtime configuration");
        applyRuntimeConfig(runtimeConfig);
        activeRuntimeConfig = runtimeConfig;
        log.debug("Startup: runtime configuration applied (storage=" + StorageType
                + ", apiEnabled=" + runtimeConfig.apiEnabled() + ", webEnabled=" + runtimeConfig.webEnabled() + ")");

        try {
            log.debug("Loading POS storage: " + StorageType + "...");
            storageManager.loadStorage(StorageType, ip, port, user, pass, db);
            log.debug("POS storage loaded successfully. Launching Inventory Service...");
            inventoryService = new InventoryService(storageManager::getStorage);
            log.debug("Startup: inventory service ready");
            log.debug("Inventory Service launched successfully. Launching User Authentication Service...");
            userService = new UserService(storageManager::getStorage);
            log.debug("Startup: user service ready; launching sales service");
            salesService = new SalesService(storageManager::getStorage, userService);
            log.debug("Startup: sales service ready");
            log.info("User authorization service is ready");
        } catch (SQLException exception) {
            log.severe("Unable to start POS storage: " + exception.getMessage());
            throw new IllegalStateException("Unable to start POS storage", exception);
        }

        try {
            if (args.length == 1 && "--create-admin".equals(args[0])) {
                log.debug("Startup: initial administrator bootstrap requested");
                bootstrapAdmin();
            } else {
                log.debug("Startup: checking administrator bootstrap state");
                if (userService.isInitialAdminRequired()) {
                    log.warning("No users exist. Run GhostPOS with --create-admin in an interactive terminal to create the first administrator.");
                }
            }
        } catch (SQLException exception) {
            log.severe("Unable to initialize user accounts: " + exception.getMessage());
            throw new IllegalStateException("Unable to initialize user accounts", exception);
        }

        boolean apiEnabled = runtimeConfig.apiEnabled();
        boolean webEnabled = runtimeConfig.webEnabled();
        String apiHost = runtimeConfig.apiHost();
        int apiPort = runtimeConfig.apiPort();
        String webHost = runtimeConfig.webHost();
        int webPort = runtimeConfig.webPort();

        if (apiEnabled) {
            log.debug("Startup: constructing register API server");
            apiServer = new ApiServer(
                    inventoryService,
                    salesService,
                    userService,
                    config,
                    log,
                    VERSION,
                    runtimeConfig.sessionTtlMinutes() * 60L,
                    systemControl()
            );
            apiServer.start(apiHost, apiPort, allowedWebOrigins(webHost, webPort));
        } else {
            log.debug("Startup: register API is disabled by configuration");
        }
        if (webEnabled) {
            String apiBaseUrl = runtimeConfig.webApiBaseUrl().trim();
            if (apiBaseUrl.isEmpty()) {
                apiBaseUrl = "http://" + browserHost(apiHost) + ":" + apiPort + "/api/v1";
            }
            log.debug("Startup: constructing management web server");
            managementWebServer = new ManagementWebServer(apiBaseUrl, log);
            managementWebServer.start(webHost, webPort, apiPort);
        } else {
            log.debug("Startup: management web interface is disabled by configuration");
        }
        log.debug("Startup: all configured services are running");
    }

    private static synchronized ApiServer.OperationResult reloadConfiguration() {
        log.debug("Configuration reload: reading registered YAML files");
        RuntimeConfig previous = activeRuntimeConfig;
        configManager.reloadAll();
        log.debug("Configuration reload: validating runtime settings");
        RuntimeConfig reloaded = readRuntimeConfig();
        applyRuntimeConfig(reloaded);
        activeRuntimeConfig = reloaded;
        boolean restartRequired = previous != null && !previous.listenerSettingsMatch(reloaded);
        log.info("Configuration files reloaded");
        return new ApiServer.OperationResult(
                restartRequired
                        ? "Configuration reloaded. Changed listener or session settings require a backend restart."
                        : "Configuration reloaded.",
                restartRequired
        );
    }

    private static synchronized ApiServer.OperationResult reloadStorage() throws SQLException {
        log.debug("Storage reload: reading and validating configuration");
        RuntimeConfig previous = activeRuntimeConfig;
        configManager.reloadAll();
        RuntimeConfig reloaded = readRuntimeConfig();
        log.debug("Storage reload: initializing replacement " + reloaded.storageType() + " backend");
        storageManager.loadStorage(
                reloaded.storageType(),
                reloaded.mysqlHost(),
                reloaded.mysqlPort(),
                reloaded.mysqlUser(),
                reloaded.mysqlPassword(),
                reloaded.mysqlDatabase()
        );
        applyRuntimeConfig(reloaded);
        activeRuntimeConfig = reloaded;
        log.debug("Storage reload: replacement backend is active");
        boolean restartRequired = previous != null && !previous.listenerSettingsMatch(reloaded);
        log.info("POS storage reloaded from configuration");
        return new ApiServer.OperationResult(
                restartRequired
                        ? "Configuration and POS storage reloaded. Changed listener or session settings require a backend restart."
                        : "Configuration and POS storage reloaded.",
                restartRequired
        );
    }

    private static ApiServer.SystemControl systemControl() {
        return new ApiServer.SystemControl() {
            @Override
            public ApiServer.OperationResult reloadConfiguration() {
                return POS_System.reloadConfiguration();
            }

            @Override
            public ApiServer.OperationResult reloadStorage() throws SQLException {
                return POS_System.reloadStorage();
            }

            @Override
            public void setDebugEnabled(boolean enabled) {
                if (POS_System.DEBUG == enabled) {
                    return;
                }
                POS_System.DEBUG = enabled;
                log.info("Debug logging " + (enabled ? "enabled" : "disabled"));
            }

            @Override
            public void shutdown() {
                requestShutdown();
            }
        };
    }

    private static void requestShutdown() {
        log.warning("Backend shutdown requested by an administrator");
        Thread shutdownThread = new Thread(() -> {
            try {
                log.debug("Shutdown: waiting briefly for the accepted response to flush");
                Thread.sleep(1_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                log.severe("Delayed backend shutdown was interrupted");
                return;
            }
            log.debug("Shutdown: exiting process");
            System.exit(0);
        }, "pos-delayed-shutdown");
        shutdownThread.setDaemon(true);
        shutdownThread.start();
    }

    private static RuntimeConfig readRuntimeConfig() {
        log.debug("Configuration: parsing and validating runtime settings");
        boolean debug = config.getBoolean("debug", false);
        String storeName = config.getString("store-name", "Demo-Store").trim();
        if (storeName.isEmpty() || storeName.length() > 100) {
            throw new IllegalArgumentException("store-name must be between 1 and 100 characters");
        }

        String storageType = config.getString("storage-type", "sqlite").trim();
        String normalizedStorageType = storageType.toLowerCase(Locale.ROOT);
        if (!"mysql".equals(normalizedStorageType)
                && !"sqlite".equals(normalizedStorageType)
                && !"sqlight".equals(normalizedStorageType)) {
            throw new IllegalArgumentException("storage-type must be mysql or sqlite");
        }
        String mysqlHost = config.getString("mysql.ip", "localhost");
        int mysqlPort = config.getInt("mysql.port", 3306);
        String mysqlUser = config.getString("mysql.user", "root");
        String mysqlPassword = config.getString("mysql.pass", "");
        String mysqlDatabase = config.getString("mysql.db", "ghostpos");
        if ("mysql".equals(normalizedStorageType)) {
            if (mysqlHost == null || mysqlHost.isBlank() || mysqlDatabase == null || mysqlDatabase.isBlank()) {
                throw new IllegalArgumentException("MySQL host and database are required");
            }
            if (mysqlPort < 1 || mysqlPort > 65_535 || mysqlUser == null || mysqlPassword == null) {
                throw new IllegalArgumentException("MySQL connection settings are invalid");
            }
        }

        boolean apiEnabled = config.getBoolean("api.enabled", true);
        String apiHost = config.getString("api.host", "127.0.0.1");
        int apiPort = config.getInt("api.port", 7070);
        int sessionTtlMinutes = config.getInt("api.session-ttl-minutes", 60);
        boolean webEnabled = config.getBoolean("web.enabled", true);
        String webHost = config.getString("web.host", "127.0.0.1");
        int webPort = config.getInt("web.port", 8080);
        String webApiBaseUrl = config.getString("web.api-base-url", "");
        List<String> webAllowedOrigins = config.getStringList("web.allowed-origins");
        if (apiHost == null || apiHost.isBlank() || webHost == null || webHost.isBlank()) {
            throw new IllegalArgumentException("API and web hosts cannot be blank");
        }
        if (apiPort < 1 || apiPort > 65_535 || apiPort == 80 || apiPort == 443 || apiPort == 8080) {
            throw new IllegalArgumentException("api.port must be valid and separate from web ports");
        }
        if (webPort < 1 || webPort > 65_535) {
            throw new IllegalArgumentException("web.port must be between 1 and 65535");
        }
        if (webEnabled && !apiEnabled) {
            throw new IllegalArgumentException("The management web interface requires api.enabled: true");
        }
        if (webEnabled && apiPort == webPort) {
            throw new IllegalArgumentException("web.port and api.port must be different");
        }
        if (sessionTtlMinutes < 5 || sessionTtlMinutes > 1_440) {
            throw new IllegalArgumentException("api.session-ttl-minutes must be between 5 and 1440");
        }
        RuntimeConfig runtimeConfig = new RuntimeConfig(
                debug,
                storeName,
                storageType,
                mysqlHost,
                mysqlPort,
                mysqlUser,
                mysqlPassword,
                mysqlDatabase,
                apiEnabled,
                apiHost,
                apiPort,
                sessionTtlMinutes,
                webEnabled,
                webHost,
                webPort,
                webApiBaseUrl,
                List.copyOf(webAllowedOrigins)
        );
        log.debug("Configuration: runtime settings validated");
        return runtimeConfig;
    }

    private static void applyRuntimeConfig(RuntimeConfig runtimeConfig) {
        DEBUG = runtimeConfig.debug();
        StoreName = runtimeConfig.storeName();
        StorageType = runtimeConfig.storageType();
        ip = runtimeConfig.mysqlHost();
        port = runtimeConfig.mysqlPort();
        user = runtimeConfig.mysqlUser();
        pass = runtimeConfig.mysqlPassword();
        db = runtimeConfig.mysqlDatabase();
    }

    private record RuntimeConfig(
            boolean debug,
            String storeName,
            String storageType,
            String mysqlHost,
            int mysqlPort,
            String mysqlUser,
            String mysqlPassword,
            String mysqlDatabase,
            boolean apiEnabled,
            String apiHost,
            int apiPort,
            int sessionTtlMinutes,
            boolean webEnabled,
            String webHost,
            int webPort,
            String webApiBaseUrl,
            List<String> webAllowedOrigins
    ) {
        private boolean listenerSettingsMatch(RuntimeConfig other) {
            return apiEnabled == other.apiEnabled
                    && Objects.equals(apiHost, other.apiHost)
                    && apiPort == other.apiPort
                    && sessionTtlMinutes == other.sessionTtlMinutes
                    && webEnabled == other.webEnabled
                    && Objects.equals(webHost, other.webHost)
                    && webPort == other.webPort
                    && Objects.equals(webApiBaseUrl, other.webApiBaseUrl)
                    && Objects.equals(webAllowedOrigins, other.webAllowedOrigins);
        }
    }

    private static List<String> allowedWebOrigins(String webHost, int webPort) {
        LinkedHashSet<String> origins = new LinkedHashSet<>(config.getStringList("web.allowed-origins"));
        if ("localhost".equalsIgnoreCase(webHost) || "127.0.0.1".equals(webHost)) {
            origins.add("http://127.0.0.1:" + webPort);
            origins.add("http://localhost:" + webPort);
        } else if (!"0.0.0.0".equals(webHost) && !"::".equals(webHost)) {
            origins.add("http://" + browserHost(webHost) + ":" + webPort);
        }
        if (origins.isEmpty()) {
            throw new IllegalArgumentException("web.allowed-origins must list the management UI origins");
        }
        return List.copyOf(origins);
    }

    private static String browserHost(String host) {
        if ("0.0.0.0".equals(host) || "::".equals(host) || host.isBlank()) {
            return "127.0.0.1";
        }
        return host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    }

    private static void bootstrapAdmin() throws SQLException {
        if (!userService.isInitialAdminRequired()) {
            throw new IllegalStateException("An administrator already exists; bootstrap is only allowed once");
        }
        Console console = System.console();
        if (console == null) {
            throw new IllegalStateException("--create-admin requires an interactive terminal");
        }

        String username = console.readLine("Administrator username: ");
        String displayName = console.readLine("Administrator display name: ");
        char[] password = console.readPassword("Administrator password (12+ characters): ");
        char[] confirmation = console.readPassword("Confirm administrator password: ");
        try {
            if (password == null || confirmation == null) {
                throw new IllegalStateException("Could not read administrator password from terminal");
            }
            if (!Arrays.equals(password, confirmation)) {
                throw new IllegalArgumentException("Administrator passwords do not match");
            }
            UserPrincipal administrator = userService.createInitialAdmin(username, password, displayName);
            console.printf("Created initial administrator account '%s'.%n", administrator.username());
            log.info("Initial system administrator account created");
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
            if (confirmation != null) {
                Arrays.fill(confirmation, '\0');
            }
        }
    }
}
