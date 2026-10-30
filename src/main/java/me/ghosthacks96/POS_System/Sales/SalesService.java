package me.ghosthacks96.POS_System.Sales;

import me.ghosthacks96.POS_System.POS_System;
import me.ghosthacks96.POS_System.Storage.PosData.PaymentLine;
import me.ghosthacks96.POS_System.Storage.PosData.Product;
import me.ghosthacks96.POS_System.Storage.PosData.SaleDetails;
import me.ghosthacks96.POS_System.Storage.PosData.SaleDraft;
import me.ghosthacks96.POS_System.Storage.PosData.SaleLine;
import me.ghosthacks96.POS_System.Storage.PosData.SaleReceipt;
import me.ghosthacks96.POS_System.Storage.PosData.SaleSummary;
import me.ghosthacks96.POS_System.Storage.PosStorage;
import me.ghosthacks96.POS_System.Users.Permission;
import me.ghosthacks96.POS_System.Users.UserPrincipal;
import me.ghosthacks96.POS_System.Users.UserService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static me.ghosthacks96.POS_System.Users.Permission.APPLY_DISCOUNTS;
import static me.ghosthacks96.POS_System.Users.Permission.PROCESS_SALES;
import static me.ghosthacks96.POS_System.Users.Permission.VIEW_REPORTS;

public final class SalesService {
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final BigDecimal MAX_CURRENCY = new BigDecimal("9999999999.99");
    private static final int MAX_LINES = 100;
    private static final int MAX_QUANTITY_PER_LINE = 10_000;

    private final Supplier<PosStorage> storageProvider;
    private final UserService users;

    public SalesService(PosStorage storage, UserService users) {
        this(() -> Objects.requireNonNull(storage, "storage"), users);
    }

    public SalesService(Supplier<PosStorage> storageProvider, UserService users) {
        this.storageProvider = Objects.requireNonNull(storageProvider, "storageProvider");
        this.users = Objects.requireNonNull(users, "users");
    }

    private PosStorage storage() {
        return Objects.requireNonNull(storageProvider.get(), "storageProvider returned null");
    }

    public CheckoutResult checkout(
            UserPrincipal actor,
            String saleNumber,
            Long customerId,
            BigDecimal requestedDiscount,
            List<CartLine> cart,
            List<Tender> tenders
    ) throws SQLException {
        POS_System.log.debug("Sales: starting checkout validation for cashier id="
                + (actor == null ? "unknown" : actor.id()));
        UserPrincipal currentActor = requirePermission(actor, PROCESS_SALES);
        validateSaleNumber(saleNumber);

        POS_System.log.debug("Sales: checking for a prior checkout with the same idempotency key");
        Optional<SaleDetails> existing = storage().getSaleByNumber(saleNumber);
        if (existing.isPresent()) {
            POS_System.log.debug("Sales: prior checkout found; validating retry payload");
            return replayOrReject(
                    existing.get(), currentActor, saleNumber, customerId, requestedDiscount, cart, tenders
            );
        }

        POS_System.log.debug("Sales: calculating totals and validating cart");
        SaleDraft draft = buildDraft(
                currentActor, saleNumber, customerId, requestedDiscount, cart, tenders
        );
        POS_System.log.debug("Sales: committing " + draft.lines().size() + " lines and "
                + draft.payments().size() + " tenders");
        try {
            SaleReceipt receipt = storage().completeSale(draft);
            POS_System.log.debug("Sales: transaction committed; loading persisted receipt");
            SaleDetails details = storage().getSaleByNumber(receipt.saleNumber())
                    .orElseThrow(() -> new SQLException("Completed sale could not be reloaded"));
            POS_System.log.debug("Sales: checkout completed");
            return new CheckoutResult(details, cashChangeDue(details), false);
        } catch (SQLException exception) {
            POS_System.log.debug("Sales: checkout returned a storage error; checking if concurrent retry committed");
            Optional<SaleDetails> committedByConcurrentRequest = storage().getSaleByNumber(saleNumber);
            if (committedByConcurrentRequest.isPresent()) {
                POS_System.log.debug("Sales: found concurrently committed checkout; validating retry payload");
                return replayOrReject(
                        committedByConcurrentRequest.get(),
                        currentActor,
                        saleNumber,
                        customerId,
                        requestedDiscount,
                        cart,
                        tenders
                );
            }
            throw exception;
        }
    }

    public SaleDetails getReceipt(UserPrincipal actor, String saleNumber) throws SQLException {
        POS_System.log.debug("Sales: checking permission to retrieve receipt");
        UserPrincipal currentActor = Objects.requireNonNull(actor, "actor");
        boolean canViewAll = users.hasPermission(currentActor, VIEW_REPORTS);
        boolean canProcessSales = users.hasPermission(currentActor, PROCESS_SALES);
        if (!canViewAll && !canProcessSales) {
            throw new SecurityException("Permission denied");
        }

        SaleDetails details = storage().getSaleByNumber(saleNumber)
                .orElseThrow(() -> new SaleNotFoundException("Sale receipt not found"));
        if (!canViewAll && details.userId() != currentActor.id()) {
            throw new SecurityException("Permission denied");
        }
        POS_System.log.debug("Sales: receipt retrieved");
        return details;
    }

    public List<SaleSummary> listRecentSales(UserPrincipal actor, int limit) throws SQLException {
        POS_System.log.debug("Sales: checking permission and loading recent sales");
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("Sale history limit must be between 1 and 500");
        }
        UserPrincipal currentActor = Objects.requireNonNull(actor, "actor");
        if (users.hasPermission(currentActor, VIEW_REPORTS)) {
            List<SaleSummary> results = storage().listRecentSales(limit, null);
            POS_System.log.debug("Sales: returned " + results.size() + " sales to report viewer");
            return results;
        }
        if (users.hasPermission(currentActor, PROCESS_SALES)) {
            List<SaleSummary> results = storage().listRecentSales(limit, currentActor.id());
            POS_System.log.debug("Sales: returned " + results.size() + " sales for cashier id=" + currentActor.id());
            return results;
        }
        throw new SecurityException("Permission denied");
    }

    public static BigDecimal cashChangeDue(SaleDetails details) {
        BigDecimal totalCash = details.payments().stream()
                .filter(payment -> TenderMethod.CASH.name().equals(payment.method()))
                .map(PaymentLine::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalPaid = details.payments().stream()
                .map(PaymentLine::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal changeDue = totalPaid.subtract(details.receipt().total());
        return changeDue.signum() <= 0 ? ZERO : changeDue.min(totalCash).setScale(2, RoundingMode.UNNECESSARY);
    }

    private SaleDraft buildDraft(
            UserPrincipal actor,
            String saleNumber,
            Long customerId,
            BigDecimal requestedDiscount,
            List<CartLine> cart,
            List<Tender> tenders
    ) throws SQLException {
        validateSaleNumber(saleNumber);
        if (cart == null || tenders == null) {
            throw new IllegalArgumentException("Cart items and tenders are required");
        }
        if (cart.isEmpty() || cart.size() > MAX_LINES) {
            throw new IllegalArgumentException("A sale must contain between 1 and " + MAX_LINES + " lines");
        }
        if (tenders.size() > 10) {
            throw new IllegalArgumentException("A sale cannot contain more than 10 tenders");
        }
        if (customerId != null) {
            if (customerId <= 0 || storage().getCustomer(customerId).isEmpty()) {
                throw new IllegalArgumentException("Customer does not exist");
            }
        }

        List<SaleLine> lines = new ArrayList<>(cart.size());
        Set<Long> productIds = new HashSet<>();
        BigDecimal subtotal = ZERO;
        for (CartLine item : cart) {
            if (item == null) {
                throw new IllegalArgumentException("Cart lines cannot be null");
            }
            if (item.productId() <= 0 || item.quantity() <= 0 || item.quantity() > MAX_QUANTITY_PER_LINE) {
                throw new IllegalArgumentException("Each cart line requires a valid product and quantity");
            }
            if (!productIds.add(item.productId())) {
                throw new IllegalArgumentException("Each product may appear only once in the cart");
            }

            Product product = storage().getProduct(item.productId())
                    .orElseThrow(() -> new IllegalArgumentException("Product does not exist"));
            POS_System.log.debug("Sales: validated product id=" + product.id() + " and stock availability");
            if (!product.active()) {
                throw new IllegalArgumentException("Product is inactive: " + product.sku());
            }
            if (product.stockQuantity() < item.quantity()) {
                throw new IllegalArgumentException("Insufficient stock for product: " + product.sku());
            }
            BigDecimal unitPrice = currency(product.price(), "Product price");
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(item.quantity()));
            if (lineTotal.compareTo(MAX_CURRENCY) > 0) {
                throw new IllegalArgumentException("Sale line total exceeds the supported currency range");
            }
            subtotal = subtotal.add(lineTotal);
            if (subtotal.compareTo(MAX_CURRENCY) > 0) {
                throw new IllegalArgumentException("Sale subtotal exceeds the supported currency range");
            }
            lines.add(new SaleLine(
                    product.id(), product.sku(), product.name(), item.quantity(), unitPrice, ZERO
            ));
        }

        BigDecimal discount = requestedDiscount == null ? ZERO : currency(requestedDiscount, "Discount");
        if (discount.signum() < 0 || discount.compareTo(subtotal) > 0) {
            throw new IllegalArgumentException("Discount must not exceed the sale subtotal");
        }
        if (discount.signum() > 0 && !users.hasPermission(actor, APPLY_DISCOUNTS)) {
            throw new SecurityException("Permission denied");
        }
        BigDecimal total = subtotal.subtract(discount);
        POS_System.log.debug("Sales: cart total calculated");
        if (total.signum() == 0 && !tenders.isEmpty()) {
            throw new IllegalArgumentException("A zero-total sale must not include a payment");
        }
        if (total.signum() > 0 && tenders.isEmpty()) {
            throw new IllegalArgumentException("At least one tender is required");
        }

        List<PaymentLine> payments = new ArrayList<>(tenders.size());
        BigDecimal paidTotal = ZERO;
        BigDecimal cashTotal = ZERO;
        for (Tender tender : tenders) {
            if (tender == null) {
                throw new IllegalArgumentException("Tenders cannot be null");
            }
            TenderMethod method = TenderMethod.parse(tender.method());
            POS_System.log.debug("Sales: validating tender method=" + method);
            BigDecimal amount = currency(tender.amount(), "Tender amount");
            if (amount.signum() <= 0) {
                throw new IllegalArgumentException("Tender amounts must be greater than zero");
            }
            String reference = normalizeReference(tender.reference());
            payments.add(new PaymentLine(method.name(), amount, reference));
            paidTotal = paidTotal.add(amount);
            if (paidTotal.compareTo(MAX_CURRENCY) > 0) {
                throw new IllegalArgumentException("Tender total exceeds the supported currency range");
            }
            if (method == TenderMethod.CASH) {
                cashTotal = cashTotal.add(amount);
            }
        }

        BigDecimal changeDue = paidTotal.subtract(total);
        if (changeDue.signum() < 0) {
            throw new IllegalArgumentException("Tender total does not cover the amount due");
        }
        if (changeDue.compareTo(cashTotal) > 0) {
            throw new IllegalArgumentException("Change can only be given from cash tender");
        }
        POS_System.log.debug("Sales: cart and tenders validated");
        return new SaleDraft(saleNumber, customerId, actor.id(), discount, ZERO, lines, payments);
    }

    private CheckoutResult replayOrReject(
            SaleDetails existing,
            UserPrincipal actor,
            String saleNumber,
            Long customerId,
            BigDecimal requestedDiscount,
            List<CartLine> cart,
            List<Tender> tenders
    ) throws SQLException {
        POS_System.log.debug("Sales: verifying idempotent retry matches the original checkout");
        if (!sameSale(existing, actor, saleNumber, customerId, requestedDiscount, cart, tenders)) {
            throw new DuplicateSaleException(
                    "This idempotency key was already used for a different sale; retrieve its receipt before retrying"
            );
        }
        POS_System.log.debug("Sales: retry matched original checkout; returning existing receipt");
        return new CheckoutResult(existing, cashChangeDue(existing), true);
    }

    private boolean sameSale(
            SaleDetails existing,
            UserPrincipal actor,
            String saleNumber,
            Long customerId,
            BigDecimal requestedDiscount,
            List<CartLine> cart,
            List<Tender> tenders
    ) throws SQLException {
        if (cart == null || tenders == null || cart.size() != existing.lines().size()
                || tenders.size() != existing.payments().size()) {
            return false;
        }
        SaleReceipt receipt = existing.receipt();
        BigDecimal discount = requestedDiscount == null ? ZERO : currency(requestedDiscount, "Discount");
        if (existing.userId() != actor.id()
                || !Objects.equals(existing.customerId(), customerId)
                || !receipt.saleNumber().equals(saleNumber)
                || receipt.discountAmount().compareTo(discount) != 0
                || receipt.taxAmount().compareTo(ZERO) != 0
                || (discount.signum() > 0 && !users.hasPermission(actor, APPLY_DISCOUNTS))) {
            return false;
        }

        Set<Long> productIds = new HashSet<>();
        for (int index = 0; index < cart.size(); index++) {
            CartLine requestedLine = cart.get(index);
            if (requestedLine == null) {
                return false;
            }
            SaleLine storedLine = existing.lines().get(index);
            if (!productIds.add(requestedLine.productId())
                    || requestedLine.productId() != storedLine.productId()
                    || requestedLine.quantity() != storedLine.quantity()) {
                return false;
            }
        }

        for (int index = 0; index < tenders.size(); index++) {
            Tender requestedTender = tenders.get(index);
            if (requestedTender == null) {
                return false;
            }
            PaymentLine storedPayment = existing.payments().get(index);
            TenderMethod method = TenderMethod.parse(requestedTender.method());
            BigDecimal amount = currency(requestedTender.amount(), "Tender amount");
            String reference = normalizeReference(requestedTender.reference());
            if (!storedPayment.method().equals(method.name())
                    || storedPayment.amount().compareTo(amount) != 0
                    || !Objects.equals(storedPayment.reference(), reference)) {
                return false;
            }
        }
        return true;
    }

    private UserPrincipal requirePermission(UserPrincipal actor, Permission permission) throws SQLException {
        Objects.requireNonNull(actor, "actor");
        UserPrincipal currentActor = users.getActiveUser(actor.id());
        if (!currentActor.hasPermission(permission)) {
            throw new SecurityException("Permission denied");
        }
        return currentActor;
    }

    private static BigDecimal currency(BigDecimal amount, String name) {
        Objects.requireNonNull(amount, name);
        try {
            BigDecimal normalized = amount.setScale(2, RoundingMode.UNNECESSARY);
            if (normalized.compareTo(MAX_CURRENCY) > 0) {
                throw new IllegalArgumentException(name + " exceeds the supported currency range");
            }
            return normalized;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + " must have no more than two decimal places");
        }
    }

    private static String normalizeReference(String reference) {
        if (reference == null || reference.isBlank()) {
            return null;
        }
        String normalized = reference.trim();
        if (normalized.length() > 120 || normalized.matches(".*\\d{13,19}.*")) {
            throw new IllegalArgumentException("Payment reference is invalid; never send card numbers or security codes");
        }
        return normalized;
    }

    private static void validateSaleNumber(String saleNumber) {
        if (saleNumber == null || saleNumber.isBlank() || saleNumber.length() > 100) {
            throw new IllegalArgumentException("A valid idempotency key is required");
        }
    }

    public record CartLine(long productId, int quantity) {
    }

    public record Tender(String method, BigDecimal amount, String reference) {
    }

    public record CheckoutResult(SaleDetails sale, BigDecimal cashChangeDue, boolean replayed) {
    }

    public enum TenderMethod {
        CASH,
        CARD,
        GIFT_CARD,
        STORE_CREDIT;

        private static TenderMethod parse(String method) {
            if (method == null) {
                throw new IllegalArgumentException("Tender method is required");
            }
            try {
                return valueOf(method.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unsupported tender method");
            }
        }
    }

    public static final class DuplicateSaleException extends IllegalStateException {
        public DuplicateSaleException(String message) {
            super(message);
        }
    }

    public static final class SaleNotFoundException extends RuntimeException {
        public SaleNotFoundException(String message) {
            super(message);
        }
    }
}
