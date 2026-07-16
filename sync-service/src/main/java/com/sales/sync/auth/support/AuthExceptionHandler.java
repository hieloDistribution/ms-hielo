package com.sales.sync.auth.support;

import com.sales.sync.auth.internal.OrderServiceUnavailable;
import com.sales.sync.auth.security.AccountLockedException;
import com.sales.sync.auth.security.InvalidCredentialsException;
import com.sales.sync.auth.security.TokenExpiredException;
import com.sales.sync.auth.security.TokenInvalidException;
import com.sales.sync.auth.security.TokenRevokedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class AuthExceptionHandler {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(AuthExceptionHandler.class);

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<Map<String, String>> onInvalidCredentials(InvalidCredentialsException ex) {
        LOG.warn("invalid credentials: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", ErrorCode.INVALID_CREDENTIALS.body()));
    }

    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<Map<String, String>> onLocked(AccountLockedException ex) {
        return ResponseEntity.status(HttpStatus.LOCKED) // 423
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", ErrorCode.ACCOUNT_LOCKED.body()));
    }

    @ExceptionHandler(TokenExpiredException.class)
    public ResponseEntity<Map<String, String>> onTokenExpired(TokenExpiredException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", ErrorCode.TOKEN_EXPIRED.body()));
    }

    @ExceptionHandler({TokenRevokedException.class, TokenInvalidException.class})
    public ResponseEntity<Map<String, String>> onTokenRevokedOrInvalid(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", ErrorCode.TOKEN_REVOKED.body()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> onValidation(MethodArgumentNotValidException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", ErrorCode.INVALID_REQUEST.body()));
    }

    @ExceptionHandler(OrderServiceUnavailable.class)
    public ResponseEntity<Map<String, String>> onOrderServiceUnavailable(OrderServiceUnavailable ex) {
        LOG.error("order-service unavailable during signup: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", ErrorCode.ORDER_SERVICE_UNAVAILABLE.body()));
    }
}
