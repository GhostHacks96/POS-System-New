package me.ghosthacks96.POS_System.Storage;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static me.ghosthacks96.POS_System.Storage.PosData.Customer;
import static me.ghosthacks96.POS_System.Storage.PosData.AuditEntry;
import static me.ghosthacks96.POS_System.Storage.PosData.Category;
import static me.ghosthacks96.POS_System.Storage.PosData.InventoryTransaction;
import static me.ghosthacks96.POS_System.Storage.PosData.PurchaseOrderDraft;
import static me.ghosthacks96.POS_System.Storage.PosData.PurchaseOrderReceipt;
import static me.ghosthacks96.POS_System.Storage.PosData.Product;
import static me.ghosthacks96.POS_System.Storage.PosData.Register;
import static me.ghosthacks96.POS_System.Storage.PosData.SaleDraft;
import static me.ghosthacks96.POS_System.Storage.PosData.SaleDetails;
import static me.ghosthacks96.POS_System.Storage.PosData.SaleReceipt;
import static me.ghosthacks96.POS_System.Storage.PosData.SaleSummary;
import static me.ghosthacks96.POS_System.Storage.PosData.Shift;
import static me.ghosthacks96.POS_System.Storage.PosData.Supplier;
import static me.ghosthacks96.POS_System.Storage.PosData.SystemUser;
import static me.ghosthacks96.POS_System.Storage.PosData.UserAccount;

public interface PosStorage extends AutoCloseable {
    void initialize() throws SQLException;

    Product saveProduct(Product product) throws SQLException;

    Product updateProductDetails(Product product) throws SQLException;

    Optional<Product> getProduct(long id) throws SQLException;

    Optional<Product> getProductBySku(String sku) throws SQLException;

    Optional<Product> getProductByBarcode(String barcode) throws SQLException;

    List<Product> listProducts(boolean activeOnly) throws SQLException;

    List<Product> listLowStockProducts() throws SQLException;

    InventoryTransaction adjustInventory(
            long productId,
            Long userId,
            int quantityDelta,
            String transactionType,
            String note,
            String reference
    ) throws SQLException;

    List<InventoryTransaction> getInventoryTransactions(long productId, int limit) throws SQLException;

    List<InventoryTransaction> getRecentInventoryTransactions(int limit) throws SQLException;

    InventoryTransaction setInventoryCount(
            long productId,
            Long userId,
            int countedQuantity,
            String note,
            String reference
    ) throws SQLException;

    SystemUser createUser(SystemUser user) throws SQLException;

    SystemUser createInitialAdmin(SystemUser admin) throws SQLException;

    Optional<SystemUser> getUserByUsername(String username) throws SQLException;

    Optional<UserAccount> getUserById(long id) throws SQLException;

    List<UserAccount> listUsers() throws SQLException;

    int countUsers() throws SQLException;

    int countActiveAdmins() throws SQLException;

    void updateUserRole(long id, String role) throws SQLException;

    void setUserActive(long id, boolean active) throws SQLException;

    void updateUserPassword(long id, String passwordHash) throws SQLException;

    void updateUserLastLogin(long id) throws SQLException;

    Customer createCustomer(Customer customer) throws SQLException;

    Optional<Customer> getCustomer(long id) throws SQLException;

    Category createCategory(Category category) throws SQLException;

    Supplier createSupplier(Supplier supplier) throws SQLException;

    Register createRegister(Register register) throws SQLException;

    Shift openShift(long registerId, long userId, java.math.BigDecimal openingCash) throws SQLException;

    Shift closeShift(long shiftId, java.math.BigDecimal closingCash) throws SQLException;

    void recordCashDrawerTransaction(
            long shiftId,
            long userId,
            String transactionType,
            java.math.BigDecimal amount,
            String description
    ) throws SQLException;

    PurchaseOrderReceipt receivePurchaseOrder(PurchaseOrderDraft purchaseOrder) throws SQLException;

    void recordAuditEntry(AuditEntry entry) throws SQLException;

    SaleReceipt completeSale(SaleDraft sale) throws SQLException;

    Optional<SaleDetails> getSaleByNumber(String saleNumber) throws SQLException;

    List<SaleSummary> listRecentSales(int limit, Long userId) throws SQLException;

    @Override
    void close() throws SQLException;
}
