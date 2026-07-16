package com.sales.sync.auth.admin;

import java.util.UUID;

/**
 * Domain-level exceptions thrown by {@link AdminService}. Mapped to
 * HTTP responses by {@link AdminExceptionHandler}.
 *
 * <p>Owner: change {@code admin-console} PR4.
 */
public final class AdminException {

    private AdminException() {}

    public static class UserNotFound extends RuntimeException {
        public UserNotFound(UUID id) { super("user_not_found: " + id); }
    }

    public static class UnknownRole extends RuntimeException {
        public UnknownRole(String role) { super("unknown_role: " + role); }
    }

    public static class InvalidRole extends RuntimeException {
        public InvalidRole(String role) { super("invalid_role: " + role); }
    }

    public static class EmailAlreadyActive extends RuntimeException {
        public EmailAlreadyActive(String email) { super("email_already_active: " + email); }
    }

    public static class WeakPassword extends RuntimeException {
        public WeakPassword() { super("weak_password"); }
    }

    public static class InvalidInvite extends RuntimeException {
        public InvalidInvite(String reason) { super(reason); }
    }

    public static class RateLimited extends RuntimeException {
        public final long retryAfterSeconds;
        public RateLimited(long retryAfterSeconds) {
            super("rate_limited");
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }
}
