package me.ghosthacks96.POS_System.Inventory;

import me.ghosthacks96.POS_System.Storage.PosStorage;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static me.ghosthacks96.POS_System.Storage.PosData.InventoryTransaction;
import static me.ghosthacks96.POS_System.Storage.PosData.Product;

public final class InventoryService {
    private final PosStorage storage;

    public InventoryService(PosStorage storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    public Product createProduct(Product product) throws SQLException {
        Objects.requireNonNull(product, "product");
        if (product.id() > 0) {
            throw new IllegalArgumentException("New products must not already have an ID");
        }
        if (product.stockQuantity() != 0) {
            throw new IllegalArgumentException(
                    "Create products with zero stock, then use receiveStock to record initial inventory"
            );
        }
        return storage.saveProduct(product);
    }

    public Product updateProduct(Product product) throws SQLException {
        Objects.requireNonNull(product, "product");
        if (product.id() <= 0) {
            throw new IllegalArgumentException("Updated products must have a valid ID");
        }
        Product current = storage.getProduct(product.id())
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
        return storage.updateProductDetails(metadataOnly);
    }

    public Optional<Product> findBySku(String sku) throws SQLException {
        requireText(sku, "SKU");
        return storage.getProductBySku(sku);
    }

    public Optional<Product> findByBarcode(String barcode) throws SQLException {
        requireText(barcode, "barcode");
        return storage.getProductByBarcode(barcode);
    }

    public List<Product> listProducts(boolean activeOnly) throws SQLException {
        return storage.listProducts(activeOnly);
    }

    public List<Product> listLowStockProducts() throws SQLException {
        return storage.listLowStockProducts();
    }

    public InventoryTransaction receiveStock(
            long productId,
            Long userId,
            int quantity,
            String reason,
            String reference
    ) throws SQLException {
        requireProductId(productId);
        requirePositiveQuantity(quantity);
        requireText(reason, "stock receipt reason");
        return storage.adjustInventory(productId, userId, quantity, "RESTOCK", reason, normalize(reference));
    }

    public InventoryTransaction removeStock(
            long productId,
            Long userId,
            int quantity,
            RemovalReason reason,
            String note,
            String reference
    ) throws SQLException {
        requireProductId(productId);
        requirePositiveQuantity(quantity);
        Objects.requireNonNull(reason, "reason");
        requireText(note, "removal note");
        return storage.adjustInventory(
                productId, userId, -quantity, reason.name(), note, normalize(reference)
        );
    }

    public InventoryTransaction adjustStock(
            long productId,
            Long userId,
            int quantityDelta,
            String reason,
            String reference
    ) throws SQLException {
        requireProductId(productId);
        if (quantityDelta == 0) {
            throw new IllegalArgumentException("Stock adjustment must change the quantity");
        }
        requireText(reason, "adjustment reason");
        return storage.adjustInventory(
                productId, userId, quantityDelta, "ADJUSTMENT", reason, normalize(reference)
        );
    }

    public InventoryTransaction recordStockCount(
            long productId,
            Long userId,
            int countedQuantity,
            String note,
            String reference
    ) throws SQLException {
        requireProductId(productId);
        if (countedQuantity < 0) {
            throw new IllegalArgumentException("Counted inventory cannot be negative");
        }
        requireText(note, "count note");
        return storage.setInventoryCount(productId, userId, countedQuantity, note, normalize(reference));
    }

    public List<InventoryTransaction> getProductHistory(long productId, int limit) throws SQLException {
        requireProductId(productId);
        requirePositiveQuantity(limit);
        return storage.getInventoryTransactions(productId, limit);
    }

    public List<InventoryTransaction> getRecentHistory(int limit) throws SQLException {
        requirePositiveQuantity(limit);
        return storage.getRecentInventoryTransactions(limit);
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
