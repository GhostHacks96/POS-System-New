package me.ghosthacks96.POS_System.Storage;

import me.ghosthacks96.POS_System.utilities.Logger;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static me.ghosthacks96.POS_System.Storage.PosData.Customer;
import static me.ghosthacks96.POS_System.Storage.PosData.AuditEntry;
import static me.ghosthacks96.POS_System.Storage.PosData.Category;
import static me.ghosthacks96.POS_System.Storage.PosData.InventoryTransaction;
import static me.ghosthacks96.POS_System.Storage.PosData.PaymentLine;
import static me.ghosthacks96.POS_System.Storage.PosData.PurchaseOrderDraft;
import static me.ghosthacks96.POS_System.Storage.PosData.PurchaseOrderLine;
import static me.ghosthacks96.POS_System.Storage.PosData.PurchaseOrderReceipt;
import static me.ghosthacks96.POS_System.Storage.PosData.Product;
import static me.ghosthacks96.POS_System.Storage.PosData.Register;
import static me.ghosthacks96.POS_System.Storage.PosData.SaleDraft;
import static me.ghosthacks96.POS_System.Storage.PosData.SaleDetails;
import static me.ghosthacks96.POS_System.Storage.PosData.SaleLine;
import static me.ghosthacks96.POS_System.Storage.PosData.SaleReceipt;
import static me.ghosthacks96.POS_System.Storage.PosData.SaleSummary;
import static me.ghosthacks96.POS_System.Storage.PosData.Shift;
import static me.ghosthacks96.POS_System.Storage.PosData.Supplier;
import static me.ghosthacks96.POS_System.Storage.PosData.SystemUser;
import static me.ghosthacks96.POS_System.Storage.PosData.UserAccount;

public abstract class JdbcStorage implements PosStorage {
    private static final String[] TABLES = {
            """
            CREATE TABLE IF NOT EXISTS categories (
                id ${ID},
                name VARCHAR(120) NOT NULL UNIQUE,
                description TEXT,
                created_at VARCHAR(32) NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS products (
                id ${ID},
                sku VARCHAR(100) NOT NULL UNIQUE,
                barcode VARCHAR(100) UNIQUE,
                name VARCHAR(200) NOT NULL,
                description TEXT,
                category_id ${LONG},
                price DECIMAL(12,2) NOT NULL,
                cost DECIMAL(12,2) NOT NULL,
                stock_quantity INTEGER NOT NULL DEFAULT 0,
                low_stock_threshold INTEGER NOT NULL DEFAULT 0,
                active BOOLEAN NOT NULL DEFAULT TRUE,
                created_at VARCHAR(32) NOT NULL,
                updated_at VARCHAR(32) NOT NULL,
                FOREIGN KEY (category_id) REFERENCES categories(id) ON DELETE SET NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS system_users (
                id ${ID},
                username VARCHAR(100) NOT NULL UNIQUE,
                password_hash VARCHAR(255) NOT NULL,
                display_name VARCHAR(200) NOT NULL,
                role VARCHAR(40) NOT NULL,
                active BOOLEAN NOT NULL DEFAULT TRUE,
                created_at VARCHAR(32) NOT NULL,
                last_login_at VARCHAR(32)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS system_user_bootstrap (
                id ${LONG} PRIMARY KEY
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS customers (
                id ${ID},
                name VARCHAR(200) NOT NULL,
                email VARCHAR(254) UNIQUE,
                phone VARCHAR(50),
                address TEXT,
                created_at VARCHAR(32) NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS inventory_transactions (
                id ${ID},
                product_id ${LONG} NOT NULL,
                user_id ${LONG},
                transaction_type VARCHAR(40) NOT NULL,
                quantity_delta INTEGER NOT NULL,
                note TEXT,
                reference VARCHAR(120),
                created_at VARCHAR(32) NOT NULL,
                FOREIGN KEY (product_id) REFERENCES products(id),
                FOREIGN KEY (user_id) REFERENCES system_users(id) ON DELETE SET NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS sales (
                id ${ID},
                sale_number VARCHAR(100) NOT NULL UNIQUE,
                customer_id ${LONG},
                user_id ${LONG} NOT NULL,
                subtotal DECIMAL(12,2) NOT NULL,
                discount_amount DECIMAL(12,2) NOT NULL,
                tax_amount DECIMAL(12,2) NOT NULL,
                total DECIMAL(12,2) NOT NULL,
                status VARCHAR(30) NOT NULL,
                created_at VARCHAR(32) NOT NULL,
                FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE SET NULL,
                FOREIGN KEY (user_id) REFERENCES system_users(id)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS sale_items (
                id ${ID},
                sale_id ${LONG} NOT NULL,
                product_id ${LONG} NOT NULL,
                sku VARCHAR(100) NOT NULL,
                product_name VARCHAR(200) NOT NULL,
                quantity INTEGER NOT NULL,
                unit_price DECIMAL(12,2) NOT NULL,
                discount_amount DECIMAL(12,2) NOT NULL,
                line_total DECIMAL(12,2) NOT NULL,
                FOREIGN KEY (sale_id) REFERENCES sales(id) ON DELETE CASCADE,
                FOREIGN KEY (product_id) REFERENCES products(id)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS payments (
                id ${ID},
                sale_id ${LONG} NOT NULL,
                method VARCHAR(40) NOT NULL,
                amount DECIMAL(12,2) NOT NULL,
                reference VARCHAR(120),
                created_at VARCHAR(32) NOT NULL,
                FOREIGN KEY (sale_id) REFERENCES sales(id) ON DELETE CASCADE
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS suppliers (
                id ${ID},
                name VARCHAR(200) NOT NULL,
                contact_name VARCHAR(200),
                email VARCHAR(254),
                phone VARCHAR(50),
                address TEXT,
                active BOOLEAN NOT NULL DEFAULT TRUE,
                created_at VARCHAR(32) NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS product_suppliers (
                product_id ${LONG} NOT NULL,
                supplier_id ${LONG} NOT NULL,
                supplier_sku VARCHAR(100),
                unit_cost DECIMAL(12,2) NOT NULL,
                PRIMARY KEY (product_id, supplier_id),
                FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE,
                FOREIGN KEY (supplier_id) REFERENCES suppliers(id) ON DELETE CASCADE
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS purchase_orders (
                id ${ID},
                supplier_id ${LONG} NOT NULL,
                user_id ${LONG} NOT NULL,
                status VARCHAR(30) NOT NULL,
                total DECIMAL(12,2) NOT NULL,
                created_at VARCHAR(32) NOT NULL,
                received_at VARCHAR(32),
                FOREIGN KEY (supplier_id) REFERENCES suppliers(id),
                FOREIGN KEY (user_id) REFERENCES system_users(id)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS purchase_order_items (
                id ${ID},
                purchase_order_id ${LONG} NOT NULL,
                product_id ${LONG} NOT NULL,
                quantity INTEGER NOT NULL,
                unit_cost DECIMAL(12,2) NOT NULL,
                FOREIGN KEY (purchase_order_id) REFERENCES purchase_orders(id) ON DELETE CASCADE,
                FOREIGN KEY (product_id) REFERENCES products(id)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS registers (
                id ${ID},
                name VARCHAR(100) NOT NULL UNIQUE,
                active BOOLEAN NOT NULL DEFAULT TRUE
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS shifts (
                id ${ID},
                register_id ${LONG} NOT NULL,
                user_id ${LONG} NOT NULL,
                opening_cash DECIMAL(12,2) NOT NULL,
                closing_cash DECIMAL(12,2),
                status VARCHAR(30) NOT NULL,
                opened_at VARCHAR(32) NOT NULL,
                closed_at VARCHAR(32),
                FOREIGN KEY (register_id) REFERENCES registers(id),
                FOREIGN KEY (user_id) REFERENCES system_users(id)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS cash_drawer_transactions (
                id ${ID},
                shift_id ${LONG} NOT NULL,
                user_id ${LONG} NOT NULL,
                transaction_type VARCHAR(40) NOT NULL,
                amount DECIMAL(12,2) NOT NULL,
                description TEXT,
                created_at VARCHAR(32) NOT NULL,
                FOREIGN KEY (shift_id) REFERENCES shifts(id),
                FOREIGN KEY (user_id) REFERENCES system_users(id)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS audit_log (
                id ${ID},
                user_id ${LONG},
                action VARCHAR(100) NOT NULL,
                entity_type VARCHAR(100) NOT NULL,
                entity_id VARCHAR(100),
                details TEXT,
                created_at VARCHAR(32) NOT NULL,
                FOREIGN KEY (user_id) REFERENCES system_users(id) ON DELETE SET NULL
            )
            """
    };

    private final Logger logger;

    protected JdbcStorage(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    protected abstract Connection openConnection() throws SQLException;

    protected abstract String idDefinition();

    protected abstract String longType();

    protected abstract String bootstrapRowSql();

    protected abstract void lockBootstrapRow(Connection connection) throws SQLException;

    @Override
    public final void initialize() throws SQLException {
        logger.debug("Database: opening connection to initialize POS schema");
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            for (String table : TABLES) {
                statement.executeUpdate(table
                        .replace("${ID}", idDefinition())
                        .replace("${LONG}", longType()));
            }
            statement.executeUpdate(bootstrapRowSql());
            logger.debug("Database: initialized " + TABLES.length + " POS schema tables");
        } catch (SQLException exception) {
            logger.severe("Unable to initialize POS database schema: " + exception.getMessage());
            throw exception;
        }
    }

    @Override
    public final Product saveProduct(Product product) throws SQLException {
        Objects.requireNonNull(product, "product");
        validateProduct(product);
        Instant now = Instant.now();
        if (product.id() <= 0) {
            String sql = """
                    INSERT INTO products
                    (sku, barcode, name, description, category_id, price, cost, stock_quantity,
                     low_stock_threshold, active, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;
            try (Connection connection = openConnection()) {
                connection.setAutoCommit(false);
                try {
                    long productId;
                    try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                        bindProduct(statement, product, now.toString(), now.toString(), false);
                        statement.executeUpdate();
                        productId = generatedId(statement);
                    }
                    if (product.stockQuantity() > 0) {
                        insertInventoryTransaction(
                                connection, productId, null, "OPENING", product.stockQuantity(),
                                "Initial stock", null, now
                        );
                    }
                    connection.commit();
                    return productWithId(product, productId);
                } catch (SQLException | RuntimeException exception) {
                    rollback(connection, exception);
                    throw exception;
                }
            } catch (SQLException exception) {
                logAndThrow("Unable to create product", exception);
                throw exception;
            }
        }

        String sql = """
                UPDATE products SET sku=?, barcode=?, name=?, description=?, category_id=?, price=?, cost=?,
                    stock_quantity=?, low_stock_threshold=?, active=?, updated_at=? WHERE id=?
                """;
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                int previousStock;
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT stock_quantity FROM products WHERE id=?")) {
                    query.setLong(1, product.id());
                    try (ResultSet result = query.executeQuery()) {
                        if (!result.next()) {
                            throw new SQLException("No product exists with id " + product.id());
                        }
                        previousStock = result.getInt("stock_quantity");
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    bindProduct(statement, product, null, now.toString(), true);
                    statement.setLong(12, product.id());
                    statement.executeUpdate();
                }
                int stockDelta = product.stockQuantity() - previousStock;
                if (stockDelta != 0) {
                    insertInventoryTransaction(
                            connection, product.id(), null, "ADJUSTMENT", stockDelta,
                            "Stock changed while updating product", null, now
                    );
                }
                connection.commit();
                return product;
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            logAndThrow("Unable to update product", exception);
            throw exception;
        }
    }

    @Override
    public final Product updateProductDetails(Product product) throws SQLException {
        Objects.requireNonNull(product, "product");
        validateProduct(product);
        if (product.id() <= 0) {
            throw new IllegalArgumentException("Updated product must have a valid ID");
        }

        String sql = """
                UPDATE products SET sku=?, barcode=?, name=?, description=?, category_id=?,
                    price=?, cost=?, low_stock_threshold=?, active=?, updated_at=? WHERE id=?
                """;
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, product.sku());
            statement.setString(2, product.barcode());
            statement.setString(3, product.name());
            statement.setString(4, product.description());
            setNullableLong(statement, 5, product.categoryId());
            statement.setBigDecimal(6, product.price());
            statement.setBigDecimal(7, product.cost());
            statement.setInt(8, product.lowStockThreshold());
            statement.setBoolean(9, product.active());
            statement.setString(10, Instant.now().toString());
            statement.setLong(11, product.id());
            if (statement.executeUpdate() == 0) {
                throw new SQLException("No product exists with id " + product.id());
            }
            return getProduct(product.id()).orElseThrow(
                    () -> new SQLException("Product disappeared while updating: " + product.id())
            );
        } catch (SQLException exception) {
            logAndThrow("Unable to update product details", exception);
            throw exception;
        }
    }

    @Override
    public final Optional<Product> getProduct(long id) throws SQLException {
        return queryProduct("SELECT * FROM products WHERE id=?", statement -> statement.setLong(1, id));
    }

    @Override
    public final Optional<Product> getProductBySku(String sku) throws SQLException {
        Objects.requireNonNull(sku, "sku");
        return queryProduct("SELECT * FROM products WHERE sku=?", statement -> statement.setString(1, sku));
    }

    @Override
    public final Optional<Product> getProductByBarcode(String barcode) throws SQLException {
        Objects.requireNonNull(barcode, "barcode");
        return queryProduct("SELECT * FROM products WHERE barcode=?", statement -> statement.setString(1, barcode));
    }

    @Override
    public final List<Product> listProducts(boolean activeOnly) throws SQLException {
        String sql = "SELECT * FROM products" + (activeOnly ? " WHERE active=TRUE" : "") + " ORDER BY name";
        List<Product> products = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                products.add(readProduct(result));
            }
            return products;
        } catch (SQLException exception) {
            logAndThrow("Unable to list products", exception);
            throw exception;
        }
    }

    @Override
    public final List<Product> listLowStockProducts() throws SQLException {
        List<Product> products = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT * FROM products
                     WHERE active=TRUE AND stock_quantity<=low_stock_threshold
                     ORDER BY stock_quantity ASC, name ASC
                     """);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                products.add(readProduct(result));
            }
            return products;
        } catch (SQLException exception) {
            logAndThrow("Unable to list low-stock products", exception);
            throw exception;
        }
    }

    @Override
    public final InventoryTransaction adjustInventory(
            long productId,
            Long userId,
            int quantityDelta,
            String transactionType,
            String note,
            String reference
    ) throws SQLException {
        if (quantityDelta == 0) {
            throw new IllegalArgumentException("Inventory adjustment must change the quantity");
        }
        requireText(transactionType, "transactionType");
        Instant now = Instant.now();
        String updateSql = "UPDATE products SET stock_quantity=stock_quantity+?, updated_at=? "
                + "WHERE id=? AND stock_quantity+?>=0";
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                int updated;
                try (PreparedStatement update = connection.prepareStatement(updateSql)) {
                    update.setInt(1, quantityDelta);
                    update.setString(2, now.toString());
                    update.setLong(3, productId);
                    update.setInt(4, quantityDelta);
                    updated = update.executeUpdate();
                }
                if (updated == 0) {
                    ensureProductExists(connection, productId);
                    throw new SQLException("Inventory adjustment would make stock negative for product " + productId);
                }
                InventoryTransaction transaction = insertInventoryTransaction(
                        connection, productId, userId, transactionType, quantityDelta, note, reference, now
                );
                connection.commit();
                return transaction;
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                if (exception instanceof SQLException sqlException) {
                    logAndThrow("Unable to adjust inventory", sqlException);
                }
                throw exception;
            }
        }
    }

    @Override
    public final List<InventoryTransaction> getInventoryTransactions(long productId, int limit) throws SQLException {
        if (limit < 1) {
            throw new IllegalArgumentException("Transaction limit must be positive");
        }
        String sql = """
                SELECT id,product_id,user_id,transaction_type,quantity_delta,note,reference,created_at
                FROM inventory_transactions WHERE product_id=? ORDER BY id DESC LIMIT ?
                """;
        List<InventoryTransaction> transactions = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, productId);
            statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    transactions.add(readInventoryTransaction(result));
                }
            }
            return transactions;
        } catch (SQLException exception) {
            logAndThrow("Unable to read inventory transactions", exception);
            throw exception;
        }
    }

    @Override
    public final List<InventoryTransaction> getRecentInventoryTransactions(int limit) throws SQLException {
        if (limit < 1) {
            throw new IllegalArgumentException("Transaction limit must be positive");
        }
        String sql = """
                SELECT id,product_id,user_id,transaction_type,quantity_delta,note,reference,created_at
                FROM inventory_transactions ORDER BY id DESC LIMIT ?
                """;
        List<InventoryTransaction> transactions = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    transactions.add(readInventoryTransaction(result));
                }
            }
            return transactions;
        } catch (SQLException exception) {
            logAndThrow("Unable to read recent inventory transactions", exception);
            throw exception;
        }
    }

    @Override
    public final InventoryTransaction setInventoryCount(
            long productId,
            Long userId,
            int countedQuantity,
            String note,
            String reference
    ) throws SQLException {
        if (countedQuantity < 0) {
            throw new IllegalArgumentException("Counted inventory cannot be negative");
        }
        requireText(note, "count note");
        Instant now = Instant.now();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                int previousQuantity;
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT stock_quantity FROM products WHERE id=?")) {
                    query.setLong(1, productId);
                    try (ResultSet result = query.executeQuery()) {
                        if (!result.next()) {
                            throw new SQLException("Product does not exist: " + productId);
                        }
                        previousQuantity = result.getInt("stock_quantity");
                    }
                }

                int delta = countedQuantity - previousQuantity;
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE products SET stock_quantity=?,updated_at=?
                        WHERE id=? AND stock_quantity=?
                        """)) {
                    update.setInt(1, countedQuantity);
                    update.setString(2, now.toString());
                    update.setLong(3, productId);
                    update.setInt(4, previousQuantity);
                    if (update.executeUpdate() == 0) {
                        throw new SQLException("Inventory changed during stock count; reload and recount");
                    }
                }

                InventoryTransaction transaction = insertInventoryTransaction(
                        connection, productId, userId, "COUNT", delta, note, reference, now
                );
                connection.commit();
                return transaction;
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                if (exception instanceof SQLException sqlException) {
                    logAndThrow("Unable to save inventory count", sqlException);
                }
                throw exception;
            }
        }
    }

    @Override
    public final SystemUser createUser(SystemUser user) throws SQLException {
        validateUser(user);
        try (Connection connection = openConnection()) {
            return insertUser(connection, user);
        } catch (SQLException exception) {
            logAndThrow("Unable to create system user", exception);
            throw exception;
        }
    }

    @Override
    public final Optional<SystemUser> getUserByUsername(String username) throws SQLException {
        Objects.requireNonNull(username, "username");
        String sql = """
                SELECT id, username, password_hash, display_name, role, active
                FROM system_users WHERE username=?
                """;
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new SystemUser(
                        result.getLong("id"),
                        result.getString("username"),
                        result.getString("password_hash"),
                        result.getString("display_name"),
                        result.getString("role"),
                        result.getBoolean("active")
                ));
            }
        } catch (SQLException exception) {
            logAndThrow("Unable to find system user", exception);
            throw exception;
        }
    }

    @Override
    public final SystemUser createInitialAdmin(SystemUser admin) throws SQLException {
        validateUser(admin);
        if (!"ADMIN".equalsIgnoreCase(admin.role()) || !admin.active()) {
            throw new IllegalArgumentException("Bootstrap account must be an active ADMIN");
        }

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                lockBootstrapRow(connection);
                if (countUsers(connection) != 0) {
                    throw new IllegalStateException("Initial administrator has already been created");
                }
                SystemUser created = insertUser(connection, admin);
                connection.commit();
                return created;
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                if (exception instanceof SQLException sqlException) {
                    logAndThrow("Unable to create initial administrator", sqlException);
                }
                throw exception;
            }
        }
    }

    @Override
    public final Optional<UserAccount> getUserById(long id) throws SQLException {
        String sql = "SELECT id,username,display_name,role,active FROM system_users WHERE id=?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readUserAccount(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            logAndThrow("Unable to find system user", exception);
            throw exception;
        }
    }

    @Override
    public final List<UserAccount> listUsers() throws SQLException {
        List<UserAccount> users = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id,username,display_name,role,active FROM system_users ORDER BY username");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                users.add(readUserAccount(result));
            }
            return users;
        } catch (SQLException exception) {
            logAndThrow("Unable to list system users", exception);
            throw exception;
        }
    }

    @Override
    public final int countUsers() throws SQLException {
        try (Connection connection = openConnection()) {
            return countUsers(connection);
        } catch (SQLException exception) {
            logAndThrow("Unable to count system users", exception);
            throw exception;
        }
    }

    @Override
    public final int countActiveAdmins() throws SQLException {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM system_users WHERE role='ADMIN' AND active=TRUE");
             ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getInt(1);
        } catch (SQLException exception) {
            logAndThrow("Unable to count active administrators", exception);
            throw exception;
        }
    }

    @Override
    public final void updateUserRole(long id, String role) throws SQLException {
        requireText(role, "role");
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                lockUserBootstrap(connection);
                boolean activeAdmin = isActiveAdmin(connection, id);
                if (activeAdmin && !"ADMIN".equalsIgnoreCase(role) && countActiveAdmins(connection) <= 1) {
                    throw new SQLException("Cannot demote the last active administrator");
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE system_users SET role=? WHERE id=?")) {
                    statement.setString(1, role);
                    statement.setLong(2, id);
                    if (statement.executeUpdate() == 0) {
                        throw new SQLException("No user exists with id " + id);
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            logAndThrow("Unable to update user role", exception);
            throw exception;
        }
    }

    @Override
    public final void setUserActive(long id, boolean active) throws SQLException {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                lockUserBootstrap(connection);
                if (!active && isActiveAdmin(connection, id) && countActiveAdmins(connection) <= 1) {
                    throw new SQLException("Cannot deactivate the last active administrator");
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE system_users SET active=? WHERE id=?")) {
                    statement.setBoolean(1, active);
                    statement.setLong(2, id);
                    if (statement.executeUpdate() == 0) {
                        throw new SQLException("No user exists with id " + id);
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            logAndThrow("Unable to update user status", exception);
            throw exception;
        }
    }

    @Override
    public final void updateUserPassword(long id, String passwordHash) throws SQLException {
        requireText(passwordHash, "passwordHash");
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE system_users SET password_hash=? WHERE id=?")) {
            statement.setString(1, passwordHash);
            statement.setLong(2, id);
            if (statement.executeUpdate() == 0) {
                throw new SQLException("No user exists with id " + id);
            }
        } catch (SQLException exception) {
            logAndThrow("Unable to update user password", exception);
            throw exception;
        }
    }

    @Override
    public final void updateUserLastLogin(long id) throws SQLException {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE system_users SET last_login_at=? WHERE id=? AND active=TRUE")) {
            statement.setString(1, Instant.now().toString());
            statement.setLong(2, id);
            if (statement.executeUpdate() == 0) {
                throw new SQLException("Active user does not exist: " + id);
            }
        } catch (SQLException exception) {
            logAndThrow("Unable to update user login time", exception);
            throw exception;
        }
    }

    @Override
    public final Customer createCustomer(Customer customer) throws SQLException {
        Objects.requireNonNull(customer, "customer");
        requireText(customer.name(), "customer name");
        String sql = "INSERT INTO customers (name,email,phone,address,created_at) VALUES (?,?,?,?,?)";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, customer.name());
            statement.setString(2, customer.email());
            statement.setString(3, customer.phone());
            statement.setString(4, customer.address());
            statement.setString(5, Instant.now().toString());
            statement.executeUpdate();
            return new Customer(
                    generatedId(statement), customer.name(), customer.email(), customer.phone(), customer.address()
            );
        } catch (SQLException exception) {
            logAndThrow("Unable to create customer", exception);
            throw exception;
        }
    }

    @Override
    public final Optional<Customer> getCustomer(long id) throws SQLException {
        String sql = "SELECT id,name,email,phone,address FROM customers WHERE id=?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Customer(
                        result.getLong("id"), result.getString("name"), result.getString("email"),
                        result.getString("phone"), result.getString("address")
                ));
            }
        } catch (SQLException exception) {
            logAndThrow("Unable to find customer", exception);
            throw exception;
        }
    }

    @Override
    public final Category createCategory(Category category) throws SQLException {
        Objects.requireNonNull(category, "category");
        requireText(category.name(), "category name");
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO categories (name,description,created_at) VALUES (?,?,?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, category.name());
            statement.setString(2, category.description());
            statement.setString(3, Instant.now().toString());
            statement.executeUpdate();
            return new Category(generatedId(statement), category.name(), category.description());
        } catch (SQLException exception) {
            logAndThrow("Unable to create product category", exception);
            throw exception;
        }
    }

    @Override
    public final Supplier createSupplier(Supplier supplier) throws SQLException {
        Objects.requireNonNull(supplier, "supplier");
        requireText(supplier.name(), "supplier name");
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO suppliers (name,contact_name,email,phone,address,active,created_at)
                     VALUES (?,?,?,?,?,?,?)
                     """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, supplier.name());
            statement.setString(2, supplier.contactName());
            statement.setString(3, supplier.email());
            statement.setString(4, supplier.phone());
            statement.setString(5, supplier.address());
            statement.setBoolean(6, supplier.active());
            statement.setString(7, Instant.now().toString());
            statement.executeUpdate();
            return new Supplier(
                    generatedId(statement), supplier.name(), supplier.contactName(), supplier.email(),
                    supplier.phone(), supplier.address(), supplier.active()
            );
        } catch (SQLException exception) {
            logAndThrow("Unable to create supplier", exception);
            throw exception;
        }
    }

    @Override
    public final Register createRegister(Register register) throws SQLException {
        Objects.requireNonNull(register, "register");
        requireText(register.name(), "register name");
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO registers (name,active) VALUES (?,?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, register.name());
            statement.setBoolean(2, register.active());
            statement.executeUpdate();
            return new Register(generatedId(statement), register.name(), register.active());
        } catch (SQLException exception) {
            logAndThrow("Unable to create register", exception);
            throw exception;
        }
    }

    @Override
    public final Shift openShift(long registerId, long userId, BigDecimal openingCash) throws SQLException {
        Objects.requireNonNull(openingCash, "openingCash");
        if (openingCash.signum() < 0) {
            throw new IllegalArgumentException("Opening cash cannot be negative");
        }
        Instant openedAt = Instant.now();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO shifts
                     (register_id,user_id,opening_cash,status,opened_at)
                     VALUES (?,?,?,'OPEN',?)
                     """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, registerId);
            statement.setLong(2, userId);
            statement.setBigDecimal(3, openingCash);
            statement.setString(4, openedAt.toString());
            statement.executeUpdate();
            return new Shift(
                    generatedId(statement), registerId, userId, openingCash, null, "OPEN", openedAt, null
            );
        } catch (SQLException exception) {
            logAndThrow("Unable to open register shift", exception);
            throw exception;
        }
    }

    @Override
    public final Shift closeShift(long shiftId, BigDecimal closingCash) throws SQLException {
        Objects.requireNonNull(closingCash, "closingCash");
        if (closingCash.signum() < 0) {
            throw new IllegalArgumentException("Closing cash cannot be negative");
        }
        Instant closedAt = Instant.now();
        try (Connection connection = openConnection()) {
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE shifts SET closing_cash=?, status='CLOSED', closed_at=?
                    WHERE id=? AND status='OPEN'
                    """)) {
                update.setBigDecimal(1, closingCash);
                update.setString(2, closedAt.toString());
                update.setLong(3, shiftId);
                if (update.executeUpdate() == 0) {
                    throw new SQLException("No open shift exists with id " + shiftId);
                }
            }
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT register_id,user_id,opening_cash,opened_at FROM shifts WHERE id=?")) {
                query.setLong(1, shiftId);
                try (ResultSet result = query.executeQuery()) {
                    if (!result.next()) {
                        throw new SQLException("Shift disappeared while closing: " + shiftId);
                    }
                    return new Shift(
                            shiftId, result.getLong("register_id"), result.getLong("user_id"),
                            result.getBigDecimal("opening_cash"), closingCash, "CLOSED",
                            Instant.parse(result.getString("opened_at")), closedAt
                    );
                }
            }
        } catch (SQLException exception) {
            logAndThrow("Unable to close register shift", exception);
            throw exception;
        }
    }

    @Override
    public final void recordCashDrawerTransaction(
            long shiftId,
            long userId,
            String transactionType,
            BigDecimal amount,
            String description
    ) throws SQLException {
        requireText(transactionType, "transactionType");
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Cash drawer transaction amount must be positive");
        }
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO cash_drawer_transactions
                     (shift_id,user_id,transaction_type,amount,description,created_at)
                     VALUES (?,?,?,?,?,?)
                     """)) {
            statement.setLong(1, shiftId);
            statement.setLong(2, userId);
            statement.setString(3, transactionType);
            statement.setBigDecimal(4, amount);
            statement.setString(5, description);
            statement.setString(6, Instant.now().toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            logAndThrow("Unable to record cash drawer transaction", exception);
            throw exception;
        }
    }

    @Override
    public final PurchaseOrderReceipt receivePurchaseOrder(PurchaseOrderDraft purchaseOrder) throws SQLException {
        Objects.requireNonNull(purchaseOrder, "purchaseOrder");
        Objects.requireNonNull(purchaseOrder.lines(), "purchase order lines");
        if (purchaseOrder.lines().isEmpty()) {
            throw new IllegalArgumentException("A purchase order must contain at least one item");
        }
        BigDecimal total = BigDecimal.ZERO;
        for (PurchaseOrderLine line : purchaseOrder.lines()) {
            Objects.requireNonNull(line, "purchase order line");
            Objects.requireNonNull(line.unitCost(), "unitCost");
            if (line.quantity() <= 0 || line.unitCost().signum() < 0) {
                throw new IllegalArgumentException("Purchase order quantities must be positive and costs nonnegative");
            }
            total = total.add(line.unitCost().multiply(BigDecimal.valueOf(line.quantity())));
        }

        Instant now = Instant.now();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                long orderId;
                try (PreparedStatement order = connection.prepareStatement("""
                        INSERT INTO purchase_orders
                        (supplier_id,user_id,status,total,created_at,received_at)
                        VALUES (?,?,'RECEIVED',?,?,?)
                        """, Statement.RETURN_GENERATED_KEYS)) {
                    order.setLong(1, purchaseOrder.supplierId());
                    order.setLong(2, purchaseOrder.userId());
                    order.setBigDecimal(3, total);
                    order.setString(4, now.toString());
                    order.setString(5, now.toString());
                    order.executeUpdate();
                    orderId = generatedId(order);
                }
                for (PurchaseOrderLine line : purchaseOrder.lines()) {
                    try (PreparedStatement item = connection.prepareStatement("""
                            INSERT INTO purchase_order_items
                            (purchase_order_id,product_id,quantity,unit_cost)
                            VALUES (?,?,?,?)
                            """)) {
                        item.setLong(1, orderId);
                        item.setLong(2, line.productId());
                        item.setInt(3, line.quantity());
                        item.setBigDecimal(4, line.unitCost());
                        item.executeUpdate();
                    }
                    try (PreparedStatement stock = connection.prepareStatement(
                            "UPDATE products SET stock_quantity=stock_quantity+?,updated_at=? WHERE id=?")) {
                        stock.setInt(1, line.quantity());
                        stock.setString(2, now.toString());
                        stock.setLong(3, line.productId());
                        if (stock.executeUpdate() == 0) {
                            throw new SQLException("Product does not exist: " + line.productId());
                        }
                    }
                    insertInventoryTransaction(
                            connection, line.productId(), purchaseOrder.userId(),
                            "PURCHASE", line.quantity(), "Purchase order " + orderId,
                            Long.toString(orderId), now
                    );
                }
                connection.commit();
                return new PurchaseOrderReceipt(orderId, total);
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                if (exception instanceof SQLException sqlException) {
                    logAndThrow("Unable to receive purchase order", sqlException);
                }
                throw exception;
            }
        }
    }

    @Override
    public final void recordAuditEntry(AuditEntry entry) throws SQLException {
        Objects.requireNonNull(entry, "entry");
        requireText(entry.action(), "audit action");
        requireText(entry.entityType(), "audit entity type");
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO audit_log
                     (user_id,action,entity_type,entity_id,details,created_at)
                     VALUES (?,?,?,?,?,?)
                     """)) {
            setNullableLong(statement, 1, entry.userId());
            statement.setString(2, entry.action());
            statement.setString(3, entry.entityType());
            statement.setString(4, entry.entityId());
            statement.setString(5, entry.details());
            statement.setString(6, Instant.now().toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            logAndThrow("Unable to record audit entry", exception);
            throw exception;
        }
    }

    @Override
    public final SaleReceipt completeSale(SaleDraft sale) throws SQLException {
        logger.debug("Database: validating sale transaction with "
                + (sale == null || sale.lines() == null ? 0 : sale.lines().size()) + " lines");
        Objects.requireNonNull(sale, "sale");
        requireText(sale.saleNumber(), "saleNumber");
        Objects.requireNonNull(sale.lines(), "sale lines");
        Objects.requireNonNull(sale.payments(), "sale payments");
        if (sale.lines().isEmpty()) {
            throw new IllegalArgumentException("A completed sale requires at least one item");
        }

        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal itemDiscountTotal = BigDecimal.ZERO;
        for (SaleLine line : sale.lines()) {
            Objects.requireNonNull(line, "sale line");
            if (line.quantity() <= 0) {
                throw new IllegalArgumentException("Sale line quantity must be greater than zero");
            }
            Objects.requireNonNull(line.unitPrice(), "unitPrice");
            requireText(line.sku(), "sale item SKU");
            requireText(line.productName(), "sale item name");
            if (line.unitPrice().signum() < 0) {
                throw new IllegalArgumentException("Sale item price cannot be negative");
            }
            BigDecimal discount = line.discountAmount() == null ? BigDecimal.ZERO : line.discountAmount();
            if (discount.signum() < 0 || discount.compareTo(line.unitPrice().multiply(BigDecimal.valueOf(line.quantity()))) > 0) {
                throw new IllegalArgumentException("Sale line discount is invalid");
            }
            subtotal = subtotal.add(line.unitPrice().multiply(BigDecimal.valueOf(line.quantity())));
            itemDiscountTotal = itemDiscountTotal.add(discount);
        }
        BigDecimal orderDiscount = sale.discountAmount() == null ? BigDecimal.ZERO : sale.discountAmount();
        BigDecimal tax = sale.taxAmount() == null ? BigDecimal.ZERO : sale.taxAmount();
        BigDecimal discount = itemDiscountTotal.add(orderDiscount);
        if (orderDiscount.signum() < 0 || tax.signum() < 0 || discount.compareTo(subtotal) > 0) {
            throw new IllegalArgumentException("Sale discount or tax amount is invalid");
        }
        BigDecimal total = subtotal.subtract(discount).add(tax);
        if (sale.payments().isEmpty() && total.signum() != 0) {
            throw new IllegalArgumentException("A sale with an amount due requires at least one payment");
        }
        BigDecimal paymentsTotal = BigDecimal.ZERO;
        BigDecimal cashPaymentsTotal = BigDecimal.ZERO;
        for (PaymentLine payment : sale.payments()) {
            Objects.requireNonNull(payment, "payment");
            requireText(payment.method(), "payment method");
            Objects.requireNonNull(payment.amount(), "payment amount");
            if (payment.amount().signum() <= 0) {
                throw new IllegalArgumentException("Payment amount must be greater than zero");
            }
            paymentsTotal = paymentsTotal.add(payment.amount());
            if ("CASH".equalsIgnoreCase(payment.method())) {
                cashPaymentsTotal = cashPaymentsTotal.add(payment.amount());
            }
        }
        BigDecimal changeDue = paymentsTotal.subtract(total);
        if (changeDue.signum() < 0 || changeDue.compareTo(cashPaymentsTotal) > 0) {
            throw new IllegalArgumentException(
                    "Payment total must cover the sale; any change must come from cash tender"
            );
        }

        Instant now = Instant.now();
        String insertSale = """
                INSERT INTO sales
                (sale_number, customer_id, user_id, subtotal, discount_amount, tax_amount, total, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'COMPLETED', ?)
                """;
        logger.debug("Database: starting atomic sale and inventory transaction");
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                long saleId;
                try (PreparedStatement statement = connection.prepareStatement(insertSale, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, sale.saleNumber());
                    setNullableLong(statement, 2, sale.customerId());
                    statement.setLong(3, sale.userId());
                    statement.setBigDecimal(4, subtotal);
                    statement.setBigDecimal(5, discount);
                    statement.setBigDecimal(6, tax);
                    statement.setBigDecimal(7, total);
                    statement.setString(8, now.toString());
                    statement.executeUpdate();
                    saleId = generatedId(statement);
                }

                for (SaleLine line : sale.lines()) {
                    int updated;
                    try (PreparedStatement stock = connection.prepareStatement(
                            "UPDATE products SET stock_quantity=stock_quantity-?, updated_at=? "
                                    + "WHERE id=? AND active=TRUE AND stock_quantity>=?")) {
                        stock.setInt(1, line.quantity());
                        stock.setString(2, now.toString());
                        stock.setLong(3, line.productId());
                        stock.setInt(4, line.quantity());
                        updated = stock.executeUpdate();
                    }
                    if (updated == 0) {
                        throw new SQLException("Product is missing, inactive, or has insufficient stock: " + line.productId());
                    }
                    BigDecimal lineDiscount = line.discountAmount() == null ? BigDecimal.ZERO : line.discountAmount();
                    BigDecimal lineTotal = line.unitPrice().multiply(BigDecimal.valueOf(line.quantity()))
                            .subtract(lineDiscount);
                    try (PreparedStatement item = connection.prepareStatement("""
                            INSERT INTO sale_items
                            (sale_id, product_id, sku, product_name, quantity, unit_price, discount_amount, line_total)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                            """)) {
                        item.setLong(1, saleId);
                        item.setLong(2, line.productId());
                        item.setString(3, line.sku());
                        item.setString(4, line.productName());
                        item.setInt(5, line.quantity());
                        item.setBigDecimal(6, line.unitPrice());
                        item.setBigDecimal(7, lineDiscount);
                        item.setBigDecimal(8, lineTotal);
                        item.executeUpdate();
                    }
                    insertInventoryTransaction(
                            connection,
                            line.productId(),
                            sale.userId(),
                            "SALE",
                            -line.quantity(),
                            "Sale " + sale.saleNumber(),
                            sale.saleNumber(),
                            now
                    );
                }

                for (PaymentLine payment : sale.payments()) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO payments (sale_id, method, amount, reference, created_at)
                            VALUES (?, ?, ?, ?, ?)
                            """)) {
                        statement.setLong(1, saleId);
                        statement.setString(2, payment.method());
                        statement.setBigDecimal(3, payment.amount());
                        statement.setString(4, payment.reference());
                        statement.setString(5, now.toString());
                        statement.executeUpdate();
                    }
                }
                connection.commit();
                logger.debug("Database: committed sale, payment, and inventory records");
                return new SaleReceipt(saleId, sale.saleNumber(), subtotal, discount, tax, total);
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                if (exception instanceof SQLException sqlException) {
                    logAndThrow("Unable to complete sale", sqlException);
                }
                throw exception;
            }
        }
    }

    @Override
    public final Optional<SaleDetails> getSaleByNumber(String saleNumber) throws SQLException {
        requireText(saleNumber, "saleNumber");
        String headerSql = """
                SELECT id, sale_number, customer_id, user_id, subtotal, discount_amount, tax_amount, total, created_at
                FROM sales
                WHERE sale_number=?
                """;
        try (Connection connection = openConnection()) {
            SaleHeader saleHeader;
            try (PreparedStatement header = connection.prepareStatement(headerSql)) {
                header.setString(1, saleNumber);
                try (ResultSet result = header.executeQuery()) {
                    if (!result.next()) {
                        return Optional.empty();
                    }

                    long rawCustomerId = result.getLong("customer_id");
                    Long customerId = result.wasNull() ? null : rawCustomerId;
                    SaleReceipt receipt = new SaleReceipt(
                            result.getLong("id"),
                            result.getString("sale_number"),
                            result.getBigDecimal("subtotal"),
                            result.getBigDecimal("discount_amount"),
                            result.getBigDecimal("tax_amount"),
                            result.getBigDecimal("total")
                    );
                    saleHeader = new SaleHeader(
                            receipt,
                            customerId,
                            result.getLong("user_id"),
                            Instant.parse(result.getString("created_at"))
                    );
                }
            }

            List<SaleLine> lines = new ArrayList<>();
            try (PreparedStatement items = connection.prepareStatement("""
                    SELECT product_id, sku, product_name, quantity, unit_price, discount_amount
                    FROM sale_items
                    WHERE sale_id=?
                    ORDER BY id
                    """)) {
                items.setLong(1, saleHeader.receipt().id());
                try (ResultSet itemResults = items.executeQuery()) {
                    while (itemResults.next()) {
                        lines.add(new SaleLine(
                                itemResults.getLong("product_id"),
                                itemResults.getString("sku"),
                                itemResults.getString("product_name"),
                                itemResults.getInt("quantity"),
                                itemResults.getBigDecimal("unit_price"),
                                itemResults.getBigDecimal("discount_amount")
                        ));
                    }
                }
            }

            List<PaymentLine> payments = new ArrayList<>();
            try (PreparedStatement paymentQuery = connection.prepareStatement("""
                    SELECT method, amount, reference
                    FROM payments
                    WHERE sale_id=?
                    ORDER BY id
                    """)) {
                paymentQuery.setLong(1, saleHeader.receipt().id());
                try (ResultSet paymentResults = paymentQuery.executeQuery()) {
                    while (paymentResults.next()) {
                        payments.add(new PaymentLine(
                                paymentResults.getString("method"),
                                paymentResults.getBigDecimal("amount"),
                                paymentResults.getString("reference")
                        ));
                    }
                }
            }
            return Optional.of(new SaleDetails(
                    saleHeader.receipt(),
                    saleHeader.customerId(),
                    saleHeader.userId(),
                    saleHeader.createdAt(),
                    lines,
                    payments
            ));
        } catch (SQLException exception) {
            logAndThrow("Unable to load sale receipt", exception);
            throw exception;
        }
    }

    @Override
    public final List<SaleSummary> listRecentSales(int limit, Long userId) throws SQLException {
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("Sale history limit must be between 1 and 500");
        }
        String sql = """
                SELECT id, sale_number, user_id, total, status, created_at
                FROM sales
                %s
                ORDER BY id DESC
                LIMIT ?
                """.formatted(userId == null ? "" : "WHERE user_id=?");
        List<SaleSummary> sales = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            if (userId != null) {
                statement.setLong(parameter++, userId);
            }
            statement.setInt(parameter, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    sales.add(new SaleSummary(
                            result.getLong("id"),
                            result.getString("sale_number"),
                            result.getLong("user_id"),
                            result.getBigDecimal("total"),
                            result.getString("status"),
                            Instant.parse(result.getString("created_at"))
                    ));
                }
            }
            return sales;
        } catch (SQLException exception) {
            logAndThrow("Unable to list sales history", exception);
            throw exception;
        }
    }

    @Override
    public void close() throws SQLException {
    }

    private record SaleHeader(SaleReceipt receipt, Long customerId, long userId, Instant createdAt) {
    }

    private Optional<Product> queryProduct(String sql, Binder binder) throws SQLException {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readProduct(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            logAndThrow("Unable to query product", exception);
            throw exception;
        }
    }

    private static UserAccount readUserAccount(ResultSet result) throws SQLException {
        return new UserAccount(
                result.getLong("id"),
                result.getString("username"),
                result.getString("display_name"),
                result.getString("role"),
                result.getBoolean("active")
        );
    }

    private static void validateUser(SystemUser user) {
        Objects.requireNonNull(user, "user");
        requireText(user.username(), "username");
        requireText(user.passwordHash(), "passwordHash");
        requireText(user.displayName(), "displayName");
        requireText(user.role(), "role");
    }

    private static SystemUser insertUser(Connection connection, SystemUser user) throws SQLException {
        String sql = """
                INSERT INTO system_users
                (username,password_hash,display_name,role,active,created_at)
                VALUES (?,?,?,?,?,?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, user.username());
            statement.setString(2, user.passwordHash());
            statement.setString(3, user.displayName());
            statement.setString(4, user.role());
            statement.setBoolean(5, user.active());
            statement.setString(6, Instant.now().toString());
            statement.executeUpdate();
            return new SystemUser(
                    generatedId(statement), user.username(), user.passwordHash(),
                    user.displayName(), user.role(), user.active()
            );
        }
    }

    private static int countUsers(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM system_users");
             ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getInt(1);
        }
    }

    private static int countActiveAdmins(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM system_users WHERE role='ADMIN' AND active=TRUE");
             ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getInt(1);
        }
    }

    private static boolean isActiveAdmin(Connection connection, long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT role,active FROM system_users WHERE id=?")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("No user exists with id " + userId);
                }
                return "ADMIN".equalsIgnoreCase(result.getString("role")) && result.getBoolean("active");
            }
        }
    }

    private void lockUserBootstrap(Connection connection) throws SQLException {
        lockBootstrapRow(connection);
    }

    private static Product readProduct(ResultSet result) throws SQLException {
        long categoryId = result.getLong("category_id");
        Long nullableCategoryId = result.wasNull() ? null : categoryId;
        return new Product(
                result.getLong("id"), result.getString("sku"), result.getString("barcode"),
                result.getString("name"), result.getString("description"), nullableCategoryId,
                result.getBigDecimal("price"), result.getBigDecimal("cost"),
                result.getInt("stock_quantity"), result.getInt("low_stock_threshold"),
                result.getBoolean("active")
        );
    }

    private static InventoryTransaction readInventoryTransaction(ResultSet result) throws SQLException {
        long rawUserId = result.getLong("user_id");
        Long userId = result.wasNull() ? null : rawUserId;
        return new InventoryTransaction(
                result.getLong("id"),
                result.getLong("product_id"),
                userId,
                result.getString("transaction_type"),
                result.getInt("quantity_delta"),
                result.getString("note"),
                result.getString("reference"),
                Instant.parse(result.getString("created_at"))
        );
    }

    private static void bindProduct(
            PreparedStatement statement,
            Product product,
            String createdAt,
            String updatedAt,
            boolean update
    ) throws SQLException {
        statement.setString(1, product.sku());
        statement.setString(2, product.barcode());
        statement.setString(3, product.name());
        statement.setString(4, product.description());
        setNullableLong(statement, 5, product.categoryId());
        statement.setBigDecimal(6, product.price());
        statement.setBigDecimal(7, product.cost());
        statement.setInt(8, product.stockQuantity());
        statement.setInt(9, product.lowStockThreshold());
        statement.setBoolean(10, product.active());
        if (update) {
            statement.setString(11, updatedAt);
        } else {
            statement.setString(11, createdAt);
            statement.setString(12, updatedAt);
        }
    }

    private static Product productWithId(Product product, long id) {
        return new Product(
                id, product.sku(), product.barcode(), product.name(), product.description(), product.categoryId(),
                product.price(), product.cost(), product.stockQuantity(), product.lowStockThreshold(), product.active()
        );
    }

    private InventoryTransaction insertInventoryTransaction(
            Connection connection,
            long productId,
            Long userId,
            String transactionType,
            int quantityDelta,
            String note,
            String reference,
            Instant createdAt
    ) throws SQLException {
        String sql = """
                INSERT INTO inventory_transactions
                (product_id, user_id, transaction_type, quantity_delta, note, reference, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, productId);
            setNullableLong(statement, 2, userId);
            statement.setString(3, transactionType);
            statement.setInt(4, quantityDelta);
            statement.setString(5, note);
            statement.setString(6, reference);
            statement.setString(7, createdAt.toString());
            statement.executeUpdate();
            return new InventoryTransaction(
                    generatedId(statement), productId, userId, transactionType, quantityDelta,
                    note, reference, createdAt
            );
        }
    }

    private static void ensureProductExists(Connection connection, long productId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM products WHERE id=?")) {
            statement.setLong(1, productId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Product does not exist: " + productId);
                }
            }
        }
    }

    private static long generatedId(PreparedStatement statement) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new SQLException("Database did not return the generated record ID");
            }
            return keys.getLong(1);
        }
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static void validateProduct(Product product) {
        requireText(product.sku(), "sku");
        requireText(product.name(), "product name");
        Objects.requireNonNull(product.price(), "price");
        Objects.requireNonNull(product.cost(), "cost");
        if (product.price().signum() < 0 || product.cost().signum() < 0) {
            throw new IllegalArgumentException("Product price and cost cannot be negative");
        }
        if (product.stockQuantity() < 0 || product.lowStockThreshold() < 0) {
            throw new IllegalArgumentException("Product stock values cannot be negative");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
    }

    private static void rollback(Connection connection, Throwable cause) {
        try {
            connection.rollback();
        } catch (SQLException rollbackException) {
            cause.addSuppressed(rollbackException);
        }
    }

    private void logAndThrow(String message, SQLException exception) {
        logger.severe(message + ": " + exception.getMessage());
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
