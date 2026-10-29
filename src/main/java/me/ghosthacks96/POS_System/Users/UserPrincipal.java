package me.ghosthacks96.POS_System.Users;

public record UserPrincipal(
        long id,
        String username,
        String displayName,
        UserRole role,
        boolean active
) {
    public boolean hasPermission(Permission permission) {
        return active && role.hasPermission(permission);
    }
}
