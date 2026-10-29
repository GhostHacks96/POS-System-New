package me.ghosthacks96.POS_System.Api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import me.ghosthacks96.POS_System.Inventory.InventoryService;
import me.ghosthacks96.POS_System.Inventory.InventoryService.RemovalReason;
import me.ghosthacks96.POS_System.Sales.SalesService;
import me.ghosthacks96.POS_System.Sales.SalesService.CartLine;
import me.ghosthacks96.POS_System.Sales.SalesService.Tender;
import me.ghosthacks96.POS_System.Storage.PosData.SaleDetails;
import me.ghosthacks96.POS_System.Storage.PosData.Product;
import me.ghosthacks96.POS_System.Users.Permission;
import me.ghosthacks96.POS_System.Users.UserPrincipal;
import me.ghosthacks96.POS_System.Users.UserRole;
import me.ghosthacks96.POS_System.Users.UserService;
import me.ghosthacks96.POS_System.utilities.AbstractConfig;
import me.ghosthacks96.POS_System.utilities.Logger;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.concurrent.ConcurrentHashMap;

public final class ApiServer implements AutoCloseable {
    private static final String API_PREFIX = "/api/v1";
    private static final String ACTOR_ATTRIBUTE = ApiServer.class.getName() + ".actor";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final InventoryService inventory;
    private final SalesService sales;
    private final UserService users;
    private final AbstractConfig config;
    private final Logger logger;
    private final String version;
    private final long sessionTtlSeconds;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private Javalin app;

    public ApiServer(
            InventoryService inventory,
            SalesService sales,
            UserService users,
            AbstractConfig config,
            Logger logger,
            String version,
            long sessionTtlSeconds
    ) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.sales = Objects.requireNonNull(sales, "sales");
        this.users = Objects.requireNonNull(users, "users");
        this.config = Objects.requireNonNull(config, "config");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.version = Objects.requireNonNull(version, "version");
        if (sessionTtlSeconds < 300 || sessionTtlSeconds > 86_400) {
            throw new IllegalArgumentException("Session TTL must be between five minutes and 24 hours");
        }
        this.sessionTtlSeconds = sessionTtlSeconds;
    }

    public synchronized void start(String host, int port) {
        start(host, port, List.of("http://127.0.0.1:8080", "http://localhost:8080"));
    }

    public synchronized void start(String host, int port, List<String> allowedOrigins) {
        if (app != null) {
            throw new IllegalStateException("API server is already running");
        }
        validateBinding(host, port);
        if (allowedOrigins == null || allowedOrigins.isEmpty()
                || allowedOrigins.stream().anyMatch(origin -> origin == null || origin.isBlank())) {
            throw new IllegalArgumentException("At least one explicit web UI origin is required for API CORS");
        }
        List<String> origins = List.copyOf(new LinkedHashSet<>(allowedOrigins));

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        app = Javalin.create(javalinConfig -> {
            javalinConfig.jsonMapper(new JavalinJackson(mapper, true));
            javalinConfig.bundledPlugins.enableCors(cors -> cors.addRule(rule -> {
                rule.allowHost(origins.get(0), origins.subList(1, origins.size()).toArray(String[]::new));
                rule.maxAge = 3_600;
            }));
        });
        configureRoutes(app);
        try {
            app.start(host, port);
            logger.info("Register API listening on " + host + ":" + port);
            if (!isLoopbackHost(host)) {
                logger.warning("Register API is exposed beyond localhost; terminate TLS at a trusted reverse proxy.");
            }
        } catch (RuntimeException exception) {
            app = null;
            throw exception;
        }
    }

    private void configureRoutes(Javalin server) {
        server.before(ctx -> {
            if ("OPTIONS".equals(ctx.method().name())) {
                return;
            }
            String path = ctx.path();
            if ((API_PREFIX + "/health").equals(path)
                    || ((API_PREFIX + "/auth/login").equals(path) && "POST".equals(ctx.method().name()))) {
                return;
            }
            authenticateRequest(ctx);
        });

        server.exception(Exception.class, (exception, ctx) -> handleException(exception, ctx));
        server.error(404, ctx -> ctx.json(new ApiError("not_found", "Endpoint not found")));

        server.get(API_PREFIX + "/health", ctx -> ctx.json(Map.of("status", "ok")));
        server.post(API_PREFIX + "/auth/login", ctx -> {
            LoginRequest request = ctx.bodyAsClass(LoginRequest.class);
            if (request.username() == null || request.password() == null) {
                throw new IllegalArgumentException("Username and password are required");
            }
            UserPrincipal user = users.authenticate(request.username(), request.password().toCharArray())
                    .orElseThrow(() -> new AuthenticationException("Invalid username or password"));
            String token = createSession(user.id());
            ctx.json(new LoginResponse(
                    token, Instant.now().plusSeconds(sessionTtlSeconds), user, permissionsFor(user)
            ));
        });
        server.post(API_PREFIX + "/auth/logout", ctx -> {
            currentUser(ctx);
            sessions.remove(tokenDigest(bearerToken(ctx)));
            ctx.json(new MessageResponse("Logged out"));
        });
        server.get(API_PREFIX + "/auth/me", ctx -> {
            UserPrincipal user = currentUser(ctx);
            ctx.json(new CurrentUserResponse(user, permissionsFor(user)));
        });
        server.post(API_PREFIX + "/auth/password", ctx -> {
            PasswordChangeRequest request = ctx.bodyAsClass(PasswordChangeRequest.class);
            if (request.currentPassword() == null || request.newPassword() == null) {
                throw new IllegalArgumentException("Current and new passwords are required");
            }
            UserPrincipal user = currentUser(ctx);
            users.changeOwnPassword(
                    user, request.currentPassword().toCharArray(), request.newPassword().toCharArray()
            );
            revokeSessions(user.id());
            ctx.json(new MessageResponse("Password changed; sign in again"));
        });

        server.post(API_PREFIX + "/sales", ctx -> {
            UserPrincipal actor = requirePermission(ctx, Permission.PROCESS_SALES);
            String idempotencyKey = ctx.header("Idempotency-Key");
            if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._:-]{1,80}")) {
                throw new IllegalArgumentException(
                        "Idempotency-Key header is required and must be 1-80 safe characters"
                );
            }
            CheckoutRequest request = ctx.bodyAsClass(CheckoutRequest.class);
            if (request == null) {
                throw new IllegalArgumentException("Checkout request is required");
            }
            List<CartLine> cart = request.items() == null ? null : request.items().stream()
                    .map(item -> item == null ? null : new CartLine(item.productId(), item.quantity()))
                    .toList();
            List<Tender> tenders = request.tenders() == null ? null : request.tenders().stream()
                    .map(tender -> tender == null ? null
                            : new Tender(tender.method(), tender.amount(), tender.reference()))
                    .toList();
            SalesService.CheckoutResult result = sales.checkout(
                    actor,
                    "SALE-" + idempotencyKey,
                    request.customerId(),
                    request.discountAmount(),
                    cart,
                    tenders
            );
            ctx.status(result.replayed() ? 200 : 201).json(toReceiptResponse(
                    result.sale(), result.cashChangeDue()
            ));
        });
        server.get(API_PREFIX + "/sales", ctx -> {
            int limit = parseLimit(ctx.queryParam("limit"));
            ctx.json(sales.listRecentSales(currentUser(ctx), limit));
        });
        server.get(API_PREFIX + "/sales/{saleNumber}/receipt", ctx -> {
            SaleDetails sale = sales.getReceipt(currentUser(ctx), ctx.pathParam("saleNumber"));
            ctx.json(toReceiptResponse(sale, SalesService.cashChangeDue(sale)));
        });

        server.get(API_PREFIX + "/products", ctx -> {
            requirePermission(ctx, Permission.VIEW_PRODUCTS);
            String activeOnlyParam = ctx.queryParam("activeOnly");
            boolean activeOnly = activeOnlyParam == null || parseBoolean(activeOnlyParam, "activeOnly");
            ctx.json(inventory.listProducts(activeOnly));
        });
        server.get(API_PREFIX + "/products/low-stock", ctx -> {
            requirePermission(ctx, Permission.VIEW_INVENTORY);
            ctx.json(inventory.listLowStockProducts());
        });
        server.get(API_PREFIX + "/products/{id}", ctx -> {
            requirePermission(ctx, Permission.VIEW_PRODUCTS);
            long id = parseId(ctx.pathParam("id"), "product ID");
            ctx.json(inventory.getProduct(id).orElseThrow(() ->
                    new ResourceNotFoundException("Product not found")));
        });
        server.post(API_PREFIX + "/products", ctx -> {
            requirePermission(ctx, Permission.MANAGE_PRODUCTS);
            ProductRequest request = ctx.bodyAsClass(ProductRequest.class);
            Product created = inventory.createProduct(request.toProduct(0));
            ctx.status(201).json(created);
        });
        server.put(API_PREFIX + "/products/{id}", ctx -> {
            requirePermission(ctx, Permission.MANAGE_PRODUCTS);
            long id = parseId(ctx.pathParam("id"), "product ID");
            ProductRequest request = ctx.bodyAsClass(ProductRequest.class);
            ctx.json(inventory.updateProduct(request.toProduct(id)));
        });

        server.get(API_PREFIX + "/inventory/transactions", ctx -> {
            requirePermission(ctx, Permission.VIEW_INVENTORY);
            ctx.json(inventory.getRecentHistory(parseLimit(ctx.queryParam("limit"))));
        });
        server.get(API_PREFIX + "/inventory/{id}/transactions", ctx -> {
            requirePermission(ctx, Permission.VIEW_INVENTORY);
            long id = parseId(ctx.pathParam("id"), "product ID");
            ctx.json(inventory.getProductHistory(id, parseLimit(ctx.queryParam("limit"))));
        });
        server.post(API_PREFIX + "/inventory/{id}/receive", ctx -> {
            UserPrincipal actor = requirePermission(ctx, Permission.RECEIVE_STOCK);
            long id = parseId(ctx.pathParam("id"), "product ID");
            StockRequest request = ctx.bodyAsClass(StockRequest.class);
            ctx.status(201).json(inventory.receiveStock(
                    id, actor.id(), request.quantity(), request.note(), request.reference()
            ));
        });
        server.post(API_PREFIX + "/inventory/{id}/remove", ctx -> {
            UserPrincipal actor = requirePermission(ctx, Permission.ADJUST_INVENTORY);
            long id = parseId(ctx.pathParam("id"), "product ID");
            StockRemovalRequest request = ctx.bodyAsClass(StockRemovalRequest.class);
            RemovalReason reason = parseRemovalReason(request.reason());
            ctx.status(201).json(inventory.removeStock(
                    id, actor.id(), request.quantity(), reason, request.note(), request.reference()
            ));
        });
        server.post(API_PREFIX + "/inventory/{id}/adjust", ctx -> {
            UserPrincipal actor = requirePermission(ctx, Permission.ADJUST_INVENTORY);
            long id = parseId(ctx.pathParam("id"), "product ID");
            StockAdjustmentRequest request = ctx.bodyAsClass(StockAdjustmentRequest.class);
            ctx.status(201).json(inventory.adjustStock(
                    id, actor.id(), request.quantityDelta(), request.reason(), request.reference()
            ));
        });
        server.post(API_PREFIX + "/inventory/{id}/count", ctx -> {
            UserPrincipal actor = requirePermission(ctx, Permission.ADJUST_INVENTORY);
            long id = parseId(ctx.pathParam("id"), "product ID");
            StockCountRequest request = ctx.bodyAsClass(StockCountRequest.class);
            ctx.status(201).json(inventory.recordStockCount(
                    id, actor.id(), request.countedQuantity(), request.note(), request.reference()
            ));
        });

        server.get(API_PREFIX + "/users", ctx -> {
            UserPrincipal actor = requirePermission(ctx, Permission.VIEW_USERS);
            ctx.json(users.listUsers(actor));
        });
        server.post(API_PREFIX + "/users", ctx -> {
            UserPrincipal actor = requirePermission(ctx, Permission.MANAGE_USERS);
            CreateUserRequest request = ctx.bodyAsClass(CreateUserRequest.class);
            if (request.password() == null) {
                throw new IllegalArgumentException("Password is required");
            }
            UserPrincipal created = users.createUser(
                    actor,
                    request.username(),
                    request.password().toCharArray(),
                    request.displayName(),
                    parseRole(request.role())
            );
            ctx.status(201).json(created);
        });
        server.patch(API_PREFIX + "/users/{id}/role", ctx -> {
            UserPrincipal actor = requirePermission(ctx, Permission.MANAGE_USERS);
            long id = parseId(ctx.pathParam("id"), "user ID");
            RoleRequest request = ctx.bodyAsClass(RoleRequest.class);
            ctx.json(users.changeRole(actor, id, parseRole(request.role())));
        });
        server.patch(API_PREFIX + "/users/{id}/active", ctx -> {
            UserPrincipal actor = requirePermission(ctx, Permission.MANAGE_USERS);
            long id = parseId(ctx.pathParam("id"), "user ID");
            ActiveRequest request = ctx.bodyAsClass(ActiveRequest.class);
            if (request.active() == null) {
                throw new IllegalArgumentException("Active state is required");
            }
            UserPrincipal updated = users.setUserActive(actor, id, request.active());
            if (!request.active()) {
                revokeSessions(id);
            }
            ctx.json(updated);
        });
        server.post(API_PREFIX + "/users/{id}/password-reset", ctx -> {
            UserPrincipal actor = requirePermission(ctx, Permission.MANAGE_USERS);
            long id = parseId(ctx.pathParam("id"), "user ID");
            PasswordResetRequest request = ctx.bodyAsClass(PasswordResetRequest.class);
            if (request.newPassword() == null) {
                throw new IllegalArgumentException("New password is required");
            }
            users.resetPassword(actor, id, request.newPassword().toCharArray());
            revokeSessions(id);
            ctx.json(new MessageResponse("Password reset; sign in again"));
        });

        server.get(API_PREFIX + "/settings", ctx -> {
            requirePermission(ctx, Permission.MANAGE_SYSTEM_SETTINGS);
            ctx.json(new PublicSettings(
                    config.getString("store-name", "Demo-Store"),
                    version
            ));
        });
        server.patch(API_PREFIX + "/settings", ctx -> {
            requirePermission(ctx, Permission.MANAGE_SYSTEM_SETTINGS);
            SettingsUpdateRequest request = ctx.bodyAsClass(SettingsUpdateRequest.class);
            if (request.storeName() == null || request.storeName().isBlank()
                    || request.storeName().trim().length() > 100) {
                throw new IllegalArgumentException("Store name must be between 1 and 100 characters");
            }
            String storeName = request.storeName().trim();
            config.set("store-name", storeName);
            config.save();
            ctx.json(new PublicSettings(storeName, version));
        });
    }

    private void authenticateRequest(io.javalin.http.Context ctx) throws SQLException {
        String token = bearerToken(ctx);
        String digest = tokenDigest(token);
        Session session = sessions.get(digest);
        if (session == null) {
            throw new AuthenticationException("Authentication required");
        }
        if (session.expiresAt().isBefore(Instant.now())) {
            sessions.remove(digest, session);
            throw new AuthenticationException("Session has expired");
        }
        UserPrincipal principal;
        try {
            principal = users.getActiveUser(session.userId());
        } catch (SecurityException exception) {
            sessions.remove(digest, session);
            throw new AuthenticationException("Authentication required");
        }
        ctx.attribute(ACTOR_ATTRIBUTE, principal);
    }

    private UserPrincipal requirePermission(io.javalin.http.Context ctx, Permission permission)
            throws SQLException {
        UserPrincipal actor = currentUser(ctx);
        if (!users.hasPermission(actor, permission)) {
            throw new SecurityException("Permission denied");
        }
        return actor;
    }

    private static UserPrincipal currentUser(io.javalin.http.Context ctx) {
        UserPrincipal actor = ctx.attribute(ACTOR_ATTRIBUTE);
        if (actor == null) {
            throw new AuthenticationException("Authentication required");
        }
        return actor;
    }

    private ReceiptResponse toReceiptResponse(SaleDetails details, BigDecimal cashChangeDue) {
        return new ReceiptResponse(
                config.getString("store-name", "Demo-Store"),
                version,
                details.receipt(),
                details.createdAt(),
                details.userId(),
                details.lines(),
                details.payments(),
                cashChangeDue,
                "TENDER_RECORDED"
        );
    }

    private List<String> permissionsFor(UserPrincipal user) {
        return user.role().permissions().stream()
                .map(Enum::name)
                .sorted()
                .toList();
    }

    private String createSession(long userId) {
        Instant now = Instant.now();
        sessions.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        Instant expiresAt = now.plusSeconds(sessionTtlSeconds);
        sessions.put(tokenDigest(token), new Session(userId, expiresAt));
        return token;
    }

    private void revokeSessions(long userId) {
        sessions.entrySet().removeIf(entry -> entry.getValue().userId() == userId);
    }

    private static String bearerToken(io.javalin.http.Context ctx) {
        String authorization = ctx.header("Authorization");
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new AuthenticationException("Authentication required");
        }
        String token = authorization.substring(7).trim();
        if (token.isEmpty()) {
            throw new AuthenticationException("Authentication required");
        }
        return token;
    }

    private static String tokenDigest(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void handleException(Exception exception, io.javalin.http.Context ctx) {
        if (exception instanceof AuthenticationException) {
            ctx.status(401).header("WWW-Authenticate", "Bearer")
                    .json(new ApiError("unauthorized", exception.getMessage()));
        } else if (exception instanceof SecurityException) {
            ctx.status(403).json(new ApiError("forbidden", "Permission denied"));
        } else if (exception instanceof ResourceNotFoundException) {
            ctx.status(404).json(new ApiError("not_found", exception.getMessage()));
        } else if (exception instanceof SalesService.SaleNotFoundException) {
            ctx.status(404).json(new ApiError("not_found", exception.getMessage()));
        } else if (exception instanceof io.javalin.http.BadRequestResponse || isJsonProcessingFailure(exception)) {
            ctx.status(400).json(new ApiError("invalid_request", "Invalid request body"));
        } else if (exception instanceof IllegalArgumentException) {
            ctx.status(400).json(new ApiError("invalid_request", exception.getMessage()));
        } else if (exception instanceof IllegalStateException) {
            ctx.status(409).json(new ApiError("conflict", exception.getMessage()));
        } else if (exception instanceof SQLException) {
            logger.severe("API storage operation failed (" + exception.getClass().getSimpleName() + ")");
            ctx.status(500).json(new ApiError("internal_error", "The request could not be completed"));
        } else {
            logger.severe("API request failed (" + exception.getClass().getSimpleName() + ")");
            ctx.status(500).json(new ApiError("internal_error", "The request could not be completed"));
        }
    }

    private static boolean isJsonProcessingFailure(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof JsonProcessingException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static void validateBinding(String host, int port) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("API host cannot be blank");
        }
        if (port < 1 || port > 65_535 || port == 80 || port == 443 || port == 8080) {
            throw new IllegalArgumentException("API port must be valid and separate from web ports 80, 443, and 8080");
        }
    }

    private static boolean isLoopbackHost(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }

    private static long parseId(String value, String name) {
        try {
            long id = Long.parseLong(value);
            if (id <= 0) {
                throw new NumberFormatException();
            }
            return id;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid " + name);
        }
    }

    private static int parseLimit(String value) {
        if (value == null) {
            return 100;
        }
        try {
            int limit = Integer.parseInt(value);
            if (limit < 1 || limit > 500) {
                throw new NumberFormatException();
            }
            return limit;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Limit must be between 1 and 500");
        }
    }

    private static boolean parseBoolean(String value, String name) {
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException(name + " must be true or false");
    }

    private static UserRole parseRole(String role) {
        if (role == null) {
            throw new IllegalArgumentException("Role is required");
        }
        try {
            return UserRole.valueOf(role.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown user role");
        }
    }

    private static RemovalReason parseRemovalReason(String reason) {
        if (reason == null) {
            throw new IllegalArgumentException("Removal reason is required");
        }
        try {
            return RemovalReason.valueOf(reason.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown removal reason");
        }
    }

    @Override
    public synchronized void close() {
        sessions.clear();
        if (app != null) {
            app.stop();
            app = null;
            logger.info("Register API stopped");
        }
    }

    private record Session(long userId, Instant expiresAt) {
    }

    private record LoginRequest(String username, String password) {
    }

    private record LoginResponse(String token, Instant expiresAt, UserPrincipal user, List<String> permissions) {
    }

    private record CurrentUserResponse(UserPrincipal user, List<String> permissions) {
    }

    private record PasswordChangeRequest(String currentPassword, String newPassword) {
    }

    private record CheckoutRequest(
            Long customerId,
            BigDecimal discountAmount,
            java.util.List<CartLineRequest> items,
            java.util.List<TenderRequest> tenders
    ) {
    }

    private record CartLineRequest(long productId, int quantity) {
    }

    private record TenderRequest(String method, BigDecimal amount, String reference) {
    }

    private record ReceiptResponse(
            String storeName,
            String systemVersion,
            me.ghosthacks96.POS_System.Storage.PosData.SaleReceipt sale,
            Instant issuedAt,
            long cashierUserId,
            java.util.List<me.ghosthacks96.POS_System.Storage.PosData.SaleLine> items,
            java.util.List<me.ghosthacks96.POS_System.Storage.PosData.PaymentLine> tenders,
            BigDecimal cashChangeDue,
            String tenderStatus
    ) {
    }

    private record ProductRequest(
            String sku,
            String barcode,
            String name,
            String description,
            Long categoryId,
            BigDecimal price,
            BigDecimal cost,
            int lowStockThreshold,
            boolean active
    ) {
        private Product toProduct(long id) {
            if (sku == null || sku.isBlank() || name == null || name.isBlank()
                    || price == null || price.signum() < 0
                    || (cost != null && cost.signum() < 0)
                    || lowStockThreshold < 0) {
                throw new IllegalArgumentException("Product SKU, name, valid price, and threshold are required");
            }
            return new Product(
                    id, sku, barcode, name, description, categoryId, price, cost, 0, lowStockThreshold, active
            );
        }
    }

    private record StockRequest(int quantity, String note, String reference) {
    }

    private record StockRemovalRequest(int quantity, String reason, String note, String reference) {
    }

    private record StockAdjustmentRequest(int quantityDelta, String reason, String reference) {
    }

    private record StockCountRequest(int countedQuantity, String note, String reference) {
    }

    private record CreateUserRequest(String username, String password, String displayName, String role) {
    }

    private record RoleRequest(String role) {
    }

    private record ActiveRequest(Boolean active) {
    }

    private record PasswordResetRequest(String newPassword) {
    }

    private record SettingsUpdateRequest(String storeName) {
    }

    private record PublicSettings(String storeName, String version) {
    }

    private record MessageResponse(String message) {
    }

    private record ApiError(String error, String message) {
    }

    private static final class AuthenticationException extends RuntimeException {
        private AuthenticationException(String message) {
            super(message);
        }
    }

    private static final class ResourceNotFoundException extends RuntimeException {
        private ResourceNotFoundException(String message) {
            super(message);
        }

    }
}
