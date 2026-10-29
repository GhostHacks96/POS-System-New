package me.ghosthacks96.POS_System.Storage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class PosData {
    private PosData() {
    }

    public record Product(
            long id,
            String sku,
            String barcode,
            String name,
            String description,
            Long categoryId,
            BigDecimal price,
            BigDecimal cost,
            int stockQuantity,
            int lowStockThreshold,
            boolean active
    ) {
    }

    public record SystemUser(
            long id,
            String username,
            String passwordHash,
            String displayName,
            String role,
            boolean active
    ) {
    }

    public record UserAccount(
            long id,
            String username,
            String displayName,
            String role,
            boolean active
    ) {
    }

    public record Customer(
            long id,
            String name,
            String email,
            String phone,
            String address
    ) {
    }

    public record Category(long id, String name, String description) {
    }

    public record Supplier(
            long id,
            String name,
            String contactName,
            String email,
            String phone,
            String address,
            boolean active
    ) {
    }

    public record Register(long id, String name, boolean active) {
    }

    public record Shift(
            long id,
            long registerId,
            long userId,
            BigDecimal openingCash,
            BigDecimal closingCash,
            String status,
            Instant openedAt,
            Instant closedAt
    ) {
    }

    public record PurchaseOrderLine(long productId, int quantity, BigDecimal unitCost) {
    }

    public record PurchaseOrderDraft(long supplierId, long userId, List<PurchaseOrderLine> lines) {
    }

    public record PurchaseOrderReceipt(long id, BigDecimal total) {
    }

    public record AuditEntry(
            Long userId,
            String action,
            String entityType,
            String entityId,
            String details
    ) {
    }

    public record InventoryTransaction(
            long id,
            long productId,
            Long userId,
            String transactionType,
            int quantityDelta,
            String note,
            String reference,
            Instant createdAt
    ) {
    }

    public record SaleLine(
            long productId,
            String sku,
            String productName,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal discountAmount
    ) {
    }

    public record PaymentLine(
            String method,
            BigDecimal amount,
            String reference
    ) {
    }

    public record SaleDraft(
            String saleNumber,
            Long customerId,
            long userId,
            BigDecimal discountAmount,
            BigDecimal taxAmount,
            List<SaleLine> lines,
            List<PaymentLine> payments
    ) {
    }

    public record SaleReceipt(
            long id,
            String saleNumber,
            BigDecimal subtotal,
            BigDecimal discountAmount,
            BigDecimal taxAmount,
            BigDecimal total
    ) {
    }

    public record SaleSummary(
            long id,
            String saleNumber,
            long userId,
            BigDecimal total,
            String status,
            Instant createdAt
    ) {
    }

    public record SaleDetails(
            SaleReceipt receipt,
            Long customerId,
            long userId,
            Instant createdAt,
            List<SaleLine> lines,
            List<PaymentLine> payments
    ) {
        public SaleDetails {
            lines = List.copyOf(lines);
            payments = List.copyOf(payments);
        }
    }
}
