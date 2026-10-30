package me.ghosthacks96.POS_System.Inventory;

import me.ghosthacks96.POS_System.POS_System;
import me.ghosthacks96.POS_System.Storage.PosStorage;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import static me.ghosthacks96.POS_System.Storage.PosData.InventoryTransaction;
import static me.ghosthacks96.POS_System.Storage.PosData.Product;

public final class InventoryService {
    private final Supplier<PosStorage> storageProvider;

    public InventoryService(PosStorage storage) {
        this(() -> Objects.requireNonNull(storage, "storage"));
    }

    public InventoryService(Supplier<PosStorage> storageProvider) {
        this.storageProvider = Objects.requireNonNull(storageProvider, "storageProvider");
    }

    private PosStorage storage() {
        return Objects.requireNonNull(storageProvider.get(), "storageProvider returned null");
    }

    public Product createProduct(Product product) throws SQLException {
        POS_System.log.debug("Inventory: validating new product");
        Objects.requireNonNull(product, "product");
        if (product.id() > 0) {
            throw new IllegalArgumentException("New products must not already have an ID");
        }
        if (product.stockQuantity() != 0) {
            throw new IllegalArgumentException(
                    "Create products with zero stock, then use receiveStock to record initial inventory"
            );
        }
        Product created = storage().saveProduct(product);
        POS_System.log.debug("Inventory: created product id=" + created.id());
        return created;
    }

    public Product updateProduct(Product product) throws SQLException {
        POS_System.log.debug("Inventory: loading product for metadata update id="
                + (product == null ? "unknown" : product.id()));
        Objects.requireNonNull(product, "product");
        if (product.id() <= 0) {
            throw new IllegalArgumentException("Updated products must have a valid ID");
        }
        Product current = storage().getProduct(product.id())
                .orElseThrow(() -> new IllegalArgumentException("Product does not exist: " + product.id()));
        Product metadataOnly = new Product(
                product.id(),
                product.sku(),
                product.barcode(),
                product.name(),
                product.description(),
                product.categoryId(),
                product.price(),
                product.cost(),
                current.stockQuantity(),
                product.lowStockThreshold(),
                product.active()
        );
        Product updated = storage().updateProductDetails(metadataOnly);
        POS_System.log.debug("Inventory: updated product metadata id=" + updated.id());
        return updated;
    }

    public Optional<Product> findBySku(String sku) throws SQLException {
        requireText(sku, "SKU");
        POS_System.log.debug("Inventory: looking up product by SKU");
        Optional<Product> result = storage().getProductBySku(sku);
        POS_System.log.debug("Inventory: SKU lookup " + (result.isPresent() ? "matched" : "found no product"));
        return result;
    }

    public Optional<Product> findByBarcode(String barcode) throws SQLException {
        requireText(barcode, "barcode");
        POS_System.log.debug("Inventory: looking up product by barcode");
        Optional<Product> result = storage().getProductByBarcode(barcode);
        POS_System.log.debug("Inventory: barcode lookup " + (result.isPresent() ? "matched" : "found no product"));
        return result;
    }

    public Optional<Product> getProduct(long productId) throws SQLException {
        requireProductId(productId);
        POS_System.log.debug("Inventory: loading product id=" + productId);
        return storage().getProduct(productId);
    }

    public List<Product> listProducts(boolean activeOnly) throws SQLException {
        POS_System.log.debug("Inventory: listing products (activeOnly=" + activeOnly + ")");
        List<Product> products = storage().listProducts(activeOnly);
        POS_System.log.debug("Inventory: returned " + products.size() + " products");
        return products;
    }

    public List<Product> listLowStockProducts() throws SQLException {
        POS_System.log.debug("Inventory: loading low-stock products");
        List<Product> products = storage().listLowStockProducts();
        POS_System.log.debug("Inventory: found " + products.size() + " low-stock products");
        return products;
    }

    public InventoryTransaction receiveStock(
            long productId,
            Long userId,
            int quantity,
            String reason,
            String reference
    ) throws SQLException {
        POS_System.log.debug("Inventory: receiving stock for product id=" + productId);
        requireProductId(productId);
        requirePositiveQuantity(quantity);
        requireText(reason, "stock receipt reason");
        InventoryTransaction transaction = storage().adjustInventory(
                productId, userId, quantity, "RESTOCK", reason, normalize(reference)
        );
        POS_System.log.debug("Inventory: stock receipt recorded transaction id=" + transaction.id());
        return transaction;
    }

    public InventoryTransaction removeStock(
            long productId,
            Long userId,
            int quantity,
            RemovalReason reason,
            String note,
            String reference
    ) throws SQLException {
        POS_System.log.debug("Inventory: removing stock for product id=" + productId);
        requireProductId(productId);
        requirePositiveQuantity(quantity);
        Objects.requireNonNull(reason, "reason");
        requireText(note, "removal note");
        InventoryTransaction transaction = storage().adjustInventory(
                productId, userId, -quantity, reason.name(), note, normalize(reference)
        );
        POS_System.log.debug("Inventory: stock removal recorded transaction id=" + transaction.id());
        return transaction;
    }

    public InventoryTransaction adjustStock(
            long productId,
            Long userId,
            int quantityDelta,
            String reason,
            String reference
    ) throws SQLException {
        POS_System.log.debug("Inventory: adjusting stock for product id=" + productId);
        requireProductId(productId);
        if (quantityDelta == 0) {
            throw new IllegalArgumentException("Stock adjustment must change the quantity");
        }
        requireText(reason, "adjustment reason");
        InventoryTransaction transaction = storage().adjustInventory(
                productId, userId, quantityDelta, "ADJUSTMENT", reason, normalize(reference)
        );
        POS_System.log.debug("Inventory: stock adjustment recorded transaction id=" + transaction.id());
        return transaction;
    }

    public InventoryTransaction recordStockCount(
            long productId,
            Long userId,
            int countedQuantity,
            String note,
            String reference
    ) throws SQLException {
        POS_System.log.debug("Inventory: recording stock count for product id=" + productId);
        requireProductId(productId);
        if (countedQuantity < 0) {
            throw new IllegalArgumentException("Counted inventory cannot be negative");
        }
        requireText(note, "count note");
        InventoryTransaction transaction = storage().setInventoryCount(
                productId, userId, countedQuantity, note, normalize(reference)
        );
        POS_System.log.debug("Inventory: stock count recorded transaction id=" + transaction.id());
        return transaction;
    }

    public List<InventoryTransaction> getProductHistory(long productId, int limit) throws SQLException {
        requireProductId(productId);
        requirePositiveQuantity(limit);
        POS_System.log.debug("Inventory: loading transaction history for product id=" + productId);
        List<InventoryTransaction> history = storage().getInventoryTransactions(productId, limit);
        POS_System.log.debug("Inventory: returned " + history.size() + " transactions");
        return history;
    }

    public List<InventoryTransaction> getRecentHistory(int limit) throws SQLException {
        requirePositiveQuantity(limit);
        POS_System.log.debug("Inventory: loading recent transaction history");
        List<InventoryTransaction> history = storage().getRecentInventoryTransactions(limit);
        POS_System.log.debug("Inventory: returned " + history.size() + " transactions");
        return history;
    }

    private static void requireProductId(long productId) {
        if (productId <= 0) {
            throw new IllegalArgumentException("Product ID must be positive");
        }
    }

    private static void requirePositiveQuantity(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public enum RemovalReason {
        DAMAGE,
        WASTE,
        THEFT,
        RETURN_TO_SUPPLIER,
        OTHER
    }
}
