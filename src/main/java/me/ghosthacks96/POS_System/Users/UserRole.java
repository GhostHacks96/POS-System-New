package me.ghosthacks96.POS_System.Users;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

public enum UserRole {
    ADMIN(EnumSet.allOf(Permission.class)),
    GENERAL_MANAGER(EnumSet.of(
            Permission.VIEW_USERS,
            Permission.MANAGE_USERS,
            Permission.VIEW_PRODUCTS,
            Permission.MANAGE_PRODUCTS,
            Permission.MANAGE_CATEGORIES,
            Permission.VIEW_INVENTORY,
            Permission.ADJUST_INVENTORY,
            Permission.RECEIVE_STOCK,
            Permission.MANAGE_SUPPLIERS,
            Permission.MANAGE_PURCHASE_ORDERS,
            Permission.PROCESS_SALES,
            Permission.VOID_SALES,
            Permission.APPLY_DISCOUNTS,
            Permission.MANAGE_CUSTOMERS,
            Permission.VIEW_REPORTS,
            Permission.MANAGE_REGISTERS,
            Permission.OPEN_SHIFT,
            Permission.CLOSE_SHIFT,
            Permission.MANAGE_SHIFTS,
            Permission.VIEW_AUDIT_LOG
    )),
    MANAGER(EnumSet.of(
            Permission.VIEW_USERS,
            Permission.MANAGE_USERS,
            Permission.VIEW_PRODUCTS,
            Permission.MANAGE_PRODUCTS,
            Permission.MANAGE_CATEGORIES,
            Permission.VIEW_INVENTORY,
            Permission.ADJUST_INVENTORY,
            Permission.RECEIVE_STOCK,
            Permission.MANAGE_SUPPLIERS,
            Permission.MANAGE_PURCHASE_ORDERS,
            Permission.PROCESS_SALES,
            Permission.VOID_SALES,
            Permission.APPLY_DISCOUNTS,
            Permission.MANAGE_CUSTOMERS,
            Permission.VIEW_REPORTS,
            Permission.OPEN_SHIFT,
            Permission.CLOSE_SHIFT,
            Permission.MANAGE_SHIFTS
    )),
    TEAM_LEAD(EnumSet.of(
            Permission.VIEW_PRODUCTS,
            Permission.VIEW_INVENTORY,
            Permission.ADJUST_INVENTORY,
            Permission.RECEIVE_STOCK,
            Permission.PROCESS_SALES,
            Permission.APPLY_DISCOUNTS,
            Permission.MANAGE_CUSTOMERS,
            Permission.OPEN_SHIFT,
            Permission.CLOSE_SHIFT
    )),
    USER(EnumSet.of(
            Permission.VIEW_PRODUCTS,
            Permission.VIEW_INVENTORY,
            Permission.PROCESS_SALES,
            Permission.MANAGE_CUSTOMERS,
            Permission.OPEN_SHIFT,
            Permission.CLOSE_SHIFT
    ));

    private final Set<Permission> permissions;

    UserRole(Set<Permission> permissions) {
        this.permissions = Set.copyOf(permissions);
    }

    public boolean hasPermission(Permission permission) {
        return permissions.contains(permission);
    }

    public Set<Permission> permissions() {
        return permissions;
    }

    public boolean canAssign(UserRole targetRole) {
        if (this == ADMIN) {
            return true;
        }
        if (this == GENERAL_MANAGER) {
            return targetRole == MANAGER || targetRole == TEAM_LEAD || targetRole == USER;
        }
        return this == MANAGER && (targetRole == TEAM_LEAD || targetRole == USER);
    }

    public static UserRole fromStorage(String role) {
        if (role == null) {
            throw new IllegalArgumentException("User role is missing");
        }
        return switch (role.trim().toUpperCase(Locale.ROOT)) {
            case "ADMIN", "ADMINISTRATOR" -> ADMIN;
            case "GENERAL_MANAGER", "GENERAL MANAGER" -> GENERAL_MANAGER;
            case "MANAGER" -> MANAGER;
            case "TEAM_LEAD", "TEAM LEAD" -> TEAM_LEAD;
            case "USER", "REGULAR_USER", "CASHIER" -> USER;
            default -> throw new IllegalArgumentException("Unknown user role: " + role);
        };
    }
}
