package com.sales.sync.auth.admin;

import com.sales.sync.auth.admin.LastAdminGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Maps {@link AdminException} (and the related {@link LastAdminGuard}
 * exceptions) to specific HTTP responses. Lives separately from
 * {@code AuthExceptionHandler} so admin errors are easy to reason about
 * and to extend.
 *
 * <p>Owner: change {@code admin-console} PR4.
 */
@RestControllerAdvice(basePackageClasses = AdminController.class)
public class AdminExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AdminExceptionHandler.class);

    @ExceptionHandler(AdminException.UserNotFound.class)
    public ResponseEntity<Map<String, String>> notFound(AdminException.UserNotFound ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "user_not_found"));
    }

    @ExceptionHandler({AdminException.UnknownRole.class, AdminException.InvalidRole.class})
    public ResponseEntity<Map<String, String>> invalidRole(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "invalid_role"));
    }

    @ExceptionHandler(AdminException.EmailAlreadyActive.class)
    public ResponseEntity<Map<String, String>> emailActive(AdminException.EmailAlreadyActive ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "email_already_active"));
    }

    @ExceptionHandler(AdminException.WeakPassword.class)
    public ResponseEntity<Map<String, String>> weakPassword(AdminException.WeakPassword ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("error", "weak_password"));
    }

    @ExceptionHandler(LastAdminGuard.CannotSelfDemoteLastAdmin.class)
    public ResponseEntity<Map<String, String>> cannotSelfDemote(LastAdminGuard.CannotSelfDemoteLastAdmin ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "cannot_self_demote_last_admin"));
    }

    @ExceptionHandler(LastAdminGuard.CannotDeactivateLastAdmin.class)
    public ResponseEntity<Map<String, String>> cannotDeactivate(LastAdminGuard.CannotDeactivateLastAdmin ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "cannot_deactivate_last_admin"));
    }

    @ExceptionHandler(AdminException.InvalidInvite.class)
    public ResponseEntity<Map<String, String>> invalidInvite(AdminException.InvalidInvite ex) {
        return ResponseEntity.status(HttpStatus.GONE)
                .body(Map.of("error", "invite_" + ex.getMessage()));
    }

    @ExceptionHandler(AdminException.InviteNotPending.class)
    public ResponseEntity<Map<String, String>> inviteNotPending(AdminException.InviteNotPending ex) {
        return ResponseEntity.status(HttpStatus.GONE)
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(AdminException.RateLimited.class)
    public ResponseEntity<Map<String, String>> rateLimited(AdminException.RateLimited ex,
                                                            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds))
                .body(Map.of("error", "rate_limited"));
    }
}
