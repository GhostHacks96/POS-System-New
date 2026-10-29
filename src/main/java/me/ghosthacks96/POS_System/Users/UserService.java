package me.ghosthacks96.POS_System.Users;

import me.ghosthacks96.POS_System.Storage.PosStorage;

import org.mindrot.jbcrypt.BCrypt;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import static me.ghosthacks96.POS_System.Storage.PosData.SystemUser;
import static me.ghosthacks96.POS_System.Storage.PosData.UserAccount;

public final class UserService {
    private static final int BCRYPT_COST = 12;
    private static final int MIN_PASSWORD_LENGTH = 12;
    private static final int BCRYPT_MAX_BYTES = 72;
    private static final String DUMMY_HASH = BCrypt.hashpw(
            "credential-timing-padding",
            BCrypt.gensalt(BCRYPT_COST)
    );

    private final PosStorage storage;

    public UserService(PosStorage storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    public UserPrincipal createInitialAdmin(String username, char[] password, String displayName)
            throws SQLException {
        try {
            String normalizedUsername = normalizeUsername(username);
            validatePassword(password);
            String normalizedDisplayName = normalizeDisplayName(displayName);
            String passwordHash = hashPassword(password);
            SystemUser created = storage.createInitialAdmin(new SystemUser(
                    0, normalizedUsername, passwordHash, normalizedDisplayName, UserRole.ADMIN.name(), true
            ));
            return toPrincipal(created);
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }

    public boolean isInitialAdminRequired() throws SQLException {
        return storage.countUsers() == 0;
    }

    public Optional<UserPrincipal> authenticate(String username, char[] password) throws SQLException {
        try {
            return authenticatePassword(username, password);
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }

    private Optional<UserPrincipal> authenticatePassword(String username, char[] password) throws SQLException {
        String normalizedUsername;
        try {
            normalizedUsername = normalizeUsername(username);
        } catch (IllegalArgumentException | NullPointerException exception) {
            checkDummyPassword(password);
            return Optional.empty();
        }

        if (password == null || !isValidPasswordLength(password)) {
            checkDummyPassword(password);
            return Optional.empty();
        }

        Optional<SystemUser> candidate = storage.getUserByUsername(normalizedUsername);
        if (candidate.isEmpty()) {
            checkDummyPassword(password);
            return Optional.empty();
        }

        SystemUser user = candidate.get();
        boolean passwordMatches;
        try {
            passwordMatches = BCrypt.checkpw(new String(password), user.passwordHash());
        } catch (IllegalArgumentException exception) {
            passwordMatches = false;
        }
        if (!passwordMatches || !user.active()) {
            return Optional.empty();
        }

        storage.updateUserLastLogin(user.id());
        return storage.getUserById(user.id()).map(this::toPrincipal);
    }

    public boolean hasPermission(UserPrincipal actor, Permission permission) throws SQLException {
        Objects.requireNonNull(permission, "permission");
        return currentActor(actor).hasPermission(permission);
    }

    public UserPrincipal getActiveUser(long userId) throws SQLException {
        UserPrincipal user = storage.getUserById(userId)
                .map(this::toPrincipal)
                .orElseThrow(() -> new SecurityException("User account no longer exists"));
        if (!user.active()) {
            throw new SecurityException("User account is inactive");
        }
        return user;
    }

    public List<UserPrincipal> listUsers(UserPrincipal actor) throws SQLException {
        requirePermission(actor, Permission.VIEW_USERS);
        return storage.listUsers().stream().map(this::toPrincipal).toList();
    }

    public UserPrincipal createUser(
            UserPrincipal actor,
            String username,
            char[] password,
            String displayName,
            UserRole role
    ) throws SQLException {
        try {
            UserPrincipal currentActor = requirePermission(actor, Permission.MANAGE_USERS);
            Objects.requireNonNull(role, "role");
            if (!currentActor.role().canAssign(role)) {
                throw new SecurityException("Your role cannot assign the " + role + " role");
            }
            validatePassword(password);
            SystemUser created = storage.createUser(new SystemUser(
                    0,
                    normalizeUsername(username),
                    hashPassword(password),
                    normalizeDisplayName(displayName),
                    role.name(),
                    true
            ));
            return toPrincipal(created);
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }

    public UserPrincipal changeRole(UserPrincipal actor, long targetUserId, UserRole newRole) throws SQLException {
        UserPrincipal currentActor = requirePermission(actor, Permission.MANAGE_USERS);
        Objects.requireNonNull(newRole, "newRole");
        if (currentActor.id() == targetUserId) {
            throw new SecurityException("You cannot change your own role");
        }
        UserPrincipal target = findUser(targetUserId);
        if (!currentActor.role().canAssign(target.role()) || !currentActor.role().canAssign(newRole)) {
            throw new SecurityException("Your role cannot manage that user's role");
        }
        if (target.role() == UserRole.ADMIN && newRole != UserRole.ADMIN
                && storage.countActiveAdmins() <= 1 && target.active()) {
            throw new IllegalStateException("Cannot demote the last active administrator");
        }
        storage.updateUserRole(targetUserId, newRole.name());
        return findUser(targetUserId);
    }

    public UserPrincipal setUserActive(UserPrincipal actor, long targetUserId, boolean active) throws SQLException {
        UserPrincipal currentActor = requirePermission(actor, Permission.MANAGE_USERS);
        if (currentActor.id() == targetUserId && !active) {
            throw new SecurityException("You cannot deactivate your own account");
        }
        UserPrincipal target = findUser(targetUserId);
        if (!currentActor.role().canAssign(target.role())) {
            throw new SecurityException("Your role cannot manage that user's account");
        }
        if (!active && target.role() == UserRole.ADMIN && target.active()
                && storage.countActiveAdmins() <= 1) {
            throw new IllegalStateException("Cannot deactivate the last active administrator");
        }
        storage.setUserActive(targetUserId, active);
        return findUser(targetUserId);
    }

    public void changeOwnPassword(UserPrincipal actor, char[] currentPassword, char[] newPassword)
            throws SQLException {
        try {
            UserPrincipal currentActor = currentActor(actor);
            Optional<SystemUser> user = storage.getUserByUsername(currentActor.username());
            if (user.isEmpty() || !isValidPasswordLength(currentPassword)
                    || !BCrypt.checkpw(new String(currentPassword), user.get().passwordHash())) {
                throw new SecurityException("Current password is incorrect");
            }
            validatePassword(newPassword);
            storage.updateUserPassword(currentActor.id(), hashPassword(newPassword));
        } finally {
            if (currentPassword != null) {
                Arrays.fill(currentPassword, '\0');
            }
            if (newPassword != null) {
                Arrays.fill(newPassword, '\0');
            }
        }
    }

    public void resetPassword(UserPrincipal actor, long targetUserId, char[] newPassword) throws SQLException {
        try {
            UserPrincipal currentActor = requirePermission(actor, Permission.MANAGE_USERS);
            UserPrincipal target = findUser(targetUserId);
            if (!currentActor.role().canAssign(target.role())) {
                throw new SecurityException("Your role cannot reset that user's password");
            }
            validatePassword(newPassword);
            storage.updateUserPassword(targetUserId, hashPassword(newPassword));
        } finally {
            if (newPassword != null) {
                Arrays.fill(newPassword, '\0');
            }
        }
    }

    private UserPrincipal requirePermission(UserPrincipal actor, Permission permission) throws SQLException {
        UserPrincipal currentActor = currentActor(actor);
        if (!currentActor.hasPermission(permission)) {
            throw new SecurityException("Permission denied: " + permission);
        }
        return currentActor;
    }

    private UserPrincipal currentActor(UserPrincipal actor) throws SQLException {
        Objects.requireNonNull(actor, "actor");
        UserPrincipal currentActor = storage.getUserById(actor.id())
                .map(this::toPrincipal)
                .orElseThrow(() -> new SecurityException("User account no longer exists"));
        if (!currentActor.active()) {
            throw new SecurityException("User account is inactive");
        }
        return currentActor;
    }

    private UserPrincipal findUser(long id) throws SQLException {
        if (id <= 0) {
            throw new IllegalArgumentException("User ID must be positive");
        }
        return storage.getUserById(id)
                .map(this::toPrincipal)
                .orElseThrow(() -> new IllegalArgumentException("User does not exist: " + id));
    }

    private UserPrincipal toPrincipal(UserAccount user) {
        return new UserPrincipal(
                user.id(), user.username(), user.displayName(), UserRole.fromStorage(user.role()), user.active()
        );
    }

    private UserPrincipal toPrincipal(SystemUser user) {
        return new UserPrincipal(
                user.id(), user.username(), user.displayName(), UserRole.fromStorage(user.role()), user.active()
        );
    }

    private static String hashPassword(char[] password) {
        return BCrypt.hashpw(new String(password), BCrypt.gensalt(BCRYPT_COST));
    }

    private static void checkDummyPassword(char[] password) {
        if (password == null) {
            BCrypt.checkpw("", DUMMY_HASH);
        } else {
            BCrypt.checkpw(new String(password), DUMMY_HASH);
        }
    }

    private static String normalizeUsername(String username) {
        Objects.requireNonNull(username, "username");
        String normalized = username.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9._-]{3,64}")) {
            throw new IllegalArgumentException(
                    "Username must be 3-64 characters and contain only letters, numbers, '.', '_' or '-'"
            );
        }
        return normalized;
    }

    private static String normalizeDisplayName(String displayName) {
        Objects.requireNonNull(displayName, "displayName");
        String normalized = displayName.trim();
        if (normalized.isEmpty() || normalized.length() > 200) {
            throw new IllegalArgumentException("Display name must be between 1 and 200 characters");
        }
        return normalized;
    }

    private static void validatePassword(char[] password) {
        if (!isValidPasswordLength(password)) {
            throw new IllegalArgumentException(
                    "Password must be at least " + MIN_PASSWORD_LENGTH
                            + " characters and no more than " + BCRYPT_MAX_BYTES + " UTF-8 bytes"
            );
        }
    }

    private static boolean isValidPasswordLength(char[] password) {
        return password != null
                && password.length >= MIN_PASSWORD_LENGTH
                && new String(password).getBytes(StandardCharsets.UTF_8).length <= BCRYPT_MAX_BYTES;
    }
}
