package com.sales.sync.auth.support;

import com.sales.sync.auth.internal.OrderServiceUnavailable;
import com.sales.sync.auth.internal.UserHasActiveVendorException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * HTTP-mapping advice for PR-2b reverse-direction integrity exceptions raised
 * out of the new {@link com.sales.sync.auth.service.UserService#delete} path.
 * Kept separate from {@link AuthExceptionHandler} so the auth-foundation code
 * is untouched; the surface area for cross-DB integrity errors is small but
 * grows in follow-up SDDs.
 */
@RestControllerAdvice
public class UserExceptionHandler {

    @ExceptionHandler(UserHasActiveVendorException.class)
    public ResponseEntity<Map<String, String>> onHasActiveVendor(UserHasActiveVendorException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", "user_has_active_vendor",
                        "userId", String.valueOf(ex.getUserId())));
    }

    @ExceptionHandler(OrderServiceUnavailable.class)
    public ResponseEntity<Map<String, String>> onOrderServiceUnavailable(OrderServiceUnavailable ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", "order_service_unavailable",
                        "message", ex.getMessage()));
    }
}